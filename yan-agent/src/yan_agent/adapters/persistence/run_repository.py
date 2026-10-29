from datetime import UTC, datetime

from sqlalchemy import func, or_, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from yan_agent.adapters.persistence.models import (
    AgentConversation,
    AgentMessage,
    AgentRun,
    AgentRunEvent,
)
from yan_agent.domain.errors import AgentErrorCode
from yan_agent.domain.qa import AgentResult, AgentStatus, RetrievalMode
from yan_agent.domain.run_events import completion_event_specs
from yan_agent.domain.runs import (
    GRAPH_VERSION,
    ConversationAccessDenied,
    ConversationContext,
    ConversationMessage,
    CreateRunCommand,
    EnqueuedRun,
    IdempotencyConflict,
    MessageRole,
    RunEvent,
    RunLeaseLost,
    RunNotFound,
    RunRecord,
    RunStatus,
    UpdatedRun,
)

TERMINAL_STATUSES = {
    RunStatus.SUCCEEDED.value,
    RunStatus.INSUFFICIENT_EVIDENCE.value,
    RunStatus.FAILED.value,
    RunStatus.CANCELLED.value,
}


class PostgresRunRepository:
    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        max_context_messages: int = 20,
    ) -> None:
        self._sessions = session_factory
        self._max_context_messages = max_context_messages

    async def enqueue(
        self,
        command: CreateRunCommand,
        run_id: str,
        message_id: str,
    ) -> EnqueuedRun:
        async with self._sessions() as session, session.begin():
            await session.execute(
                insert(AgentConversation)
                .values(
                    id=command.conversation_id,
                    user_id=command.user_id,
                    knowledge_base_id=command.knowledge_base_id,
                    context_version=0,
                    status="ACTIVE",
                )
                .on_conflict_do_nothing(index_elements=[AgentConversation.id])
            )
            conversation = await self._locked_conversation(session, command.conversation_id)
            self._assert_conversation_scope(
                conversation,
                command.user_id,
                command.knowledge_base_id,
            )

            inserted_run_id = await session.scalar(
                insert(AgentRun)
                .values(
                    id=run_id,
                    client_request_id=command.client_request_id,
                    conversation_id=command.conversation_id,
                    user_id=command.user_id,
                    status=RunStatus.QUEUED.value,
                    graph_version=GRAPH_VERSION,
                    question=command.question,
                    requested_mode=command.requested_mode.value,
                    context_version=conversation.context_version + 1,
                    query_ids=[],
                    result={},
                    attempt_count=0,
                    traceparent=command.traceparent,
                    tracestate=command.tracestate,
                )
                .on_conflict_do_nothing(constraint="uk_agent_run_user_request")
                .returning(AgentRun.id)
            )
            if inserted_run_id is None:
                existing = await session.scalar(
                    select(AgentRun).where(
                        AgentRun.user_id == command.user_id,
                        AgentRun.client_request_id == command.client_request_id,
                    )
                )
                if existing is None:
                    raise RuntimeError("Conflicting Agent run disappeared")
                self._assert_same_request(existing, command)
                context = await self._load_context(session, conversation)
                return EnqueuedRun(
                    run=self._to_run_record(existing, conversation.knowledge_base_id),
                    context=context,
                    created=False,
                )

            session.add(
                AgentMessage(
                    id=message_id,
                    conversation_id=command.conversation_id,
                    run_id=run_id,
                    role=MessageRole.USER.value,
                    content=command.question,
                    citations=[],
                    token_usage={},
                )
            )
            conversation.context_version += 1
            await session.flush()
            await self._append_event(
                session,
                run_id,
                "run.queued",
                {"status": RunStatus.QUEUED.value},
            )
            created = await session.get(AgentRun, run_id)
            if created is None:
                raise RuntimeError("Created Agent run could not be loaded")
            context = await self._load_context(session, conversation)
            return EnqueuedRun(
                run=self._to_run_record(created, conversation.knowledge_base_id),
                context=context,
                created=True,
            )

    async def load_context(self, conversation_id: str, user_id: int) -> ConversationContext:
        async with self._sessions() as session:
            conversation = await session.get(AgentConversation, conversation_id)
            if conversation is None or conversation.user_id != user_id:
                raise RunNotFound("Conversation not found")
            return await self._load_context(session, conversation)

    async def get_run(self, run_id: str, user_id: int) -> RunRecord:
        async with self._sessions() as session:
            row = (
                await session.execute(
                    select(AgentRun, AgentConversation.knowledge_base_id)
                    .join(AgentConversation, AgentConversation.id == AgentRun.conversation_id)
                    .where(AgentRun.id == run_id, AgentRun.user_id == user_id)
                )
            ).one_or_none()
            if row is None:
                raise RunNotFound("Run not found")
            return self._to_run_record(row[0], row[1])

    async def list_events(
        self,
        run_id: str,
        user_id: int,
        after_sequence: int = 0,
    ) -> list[RunEvent]:
        async with self._sessions() as session:
            owned = await session.scalar(
                select(AgentRun.id).where(AgentRun.id == run_id, AgentRun.user_id == user_id)
            )
            if owned is None:
                raise RunNotFound("Run not found")
            rows = list(
                await session.scalars(
                    select(AgentRunEvent)
                    .where(
                        AgentRunEvent.run_id == run_id,
                        AgentRunEvent.sequence > after_sequence,
                    )
                    .order_by(AgentRunEvent.sequence)
                )
            )
            return [
                RunEvent(
                    sequence=row.sequence,
                    event_type=row.event_type,
                    payload=row.payload,
                    created_at=row.created_at,
                )
                for row in rows
            ]

    async def claim_next(
        self,
        worker_id: str,
        now: datetime,
        lease_until: datetime,
    ) -> RunRecord | None:
        async with self._sessions() as session, session.begin():
            row = (
                await session.execute(
                    select(AgentRun, AgentConversation.knowledge_base_id)
                    .join(AgentConversation, AgentConversation.id == AgentRun.conversation_id)
                    .where(
                        or_(
                            AgentRun.status == RunStatus.QUEUED.value,
                            (
                                (AgentRun.status == RunStatus.RUNNING.value)
                                & (AgentRun.lease_until.is_not(None))
                                & (AgentRun.lease_until <= now)
                            ),
                        )
                    )
                    .order_by(AgentRun.created_at, AgentRun.id)
                    .with_for_update(skip_locked=True, of=AgentRun)
                    .limit(1)
                )
            ).one_or_none()
            if row is None:
                return None
            run, knowledge_base_id = row
            run.status = RunStatus.RUNNING.value
            run.current_node = "claimed"
            run.attempt_count += 1
            run.lease_owner = worker_id
            run.lease_until = lease_until
            run.started_at = run.started_at or now
            await session.flush()
            await self._append_event(
                session,
                run.id,
                "run.started",
                {
                    "status": RunStatus.RUNNING.value,
                    "attemptCount": run.attempt_count,
                },
                now,
            )
            return self._to_run_record(run, knowledge_base_id)

    async def complete(
        self,
        run_id: str,
        worker_id: str,
        result: AgentResult,
        message_id: str,
        now: datetime,
    ) -> ConversationContext:
        async with self._sessions() as session, session.begin():
            run = await self._locked_run(session, run_id)
            conversation = await self._locked_conversation(session, run.conversation_id)
            if run.status in TERMINAL_STATUSES:
                return await self._load_context(session, conversation)
            self._assert_lease(run, worker_id)
            if result.status is AgentStatus.CLARIFICATION_REQUIRED:
                raise ValueError("Clarification result must use mark_waiting")

            session.add(
                AgentMessage(
                    id=message_id,
                    conversation_id=run.conversation_id,
                    run_id=run.id,
                    role=MessageRole.ASSISTANT.value,
                    content=result.answer,
                    citations=[item.model_dump(mode="json") for item in result.citations],
                    token_usage={},
                )
            )
            conversation.context_version += 1
            run.status = RunStatus(result.status.value).value
            run.current_node = "completed"
            run.query_ids = result.query_ids
            run.result = result.model_dump(mode="json")
            run.lease_owner = None
            run.lease_until = None
            run.completed_at = now
            for event_type, payload in completion_event_specs(result):
                await self._append_event(session, run.id, event_type, payload, now)
            await session.flush()
            return await self._load_context(session, conversation)

    async def renew_lease(
        self,
        run_id: str,
        worker_id: str,
        now: datetime,
        lease_until: datetime,
    ) -> bool:
        async with self._sessions() as session, session.begin():
            run = await session.scalar(
                select(AgentRun).where(AgentRun.id == run_id).with_for_update()
            )
            if (
                run is None
                or run.status != RunStatus.RUNNING.value
                or run.lease_owner != worker_id
                or run.lease_until is None
                or run.lease_until <= now
            ):
                return False
            run.lease_until = lease_until
            return True

    async def mark_waiting(
        self,
        run_id: str,
        worker_id: str,
        result: AgentResult,
    ) -> ConversationContext:
        async with self._sessions() as session, session.begin():
            run = await self._locked_run(session, run_id)
            self._assert_lease(run, worker_id)
            run.status = RunStatus.WAITING_INPUT.value
            run.current_node = "interrupt_for_clarification"
            run.query_ids = result.query_ids
            run.result = result.model_dump(mode="json")
            run.lease_owner = None
            run.lease_until = None
            conversation = await self._locked_conversation(session, run.conversation_id)
            await self._append_event(
                session,
                run.id,
                "run.waiting_input",
                {
                    "status": RunStatus.WAITING_INPUT.value,
                    "queryIds": result.query_ids,
                },
                datetime.now(UTC),
            )
            return await self._load_context(session, conversation)

    async def fail(
        self,
        run_id: str,
        worker_id: str,
        error_code: AgentErrorCode,
        retryable: bool,
        safe_message: str,
        now: datetime,
    ) -> ConversationContext:
        async with self._sessions() as session, session.begin():
            run = await self._locked_run(session, run_id)
            conversation = await self._locked_conversation(session, run.conversation_id)
            if run.status in TERMINAL_STATUSES:
                return await self._load_context(session, conversation)
            self._assert_lease(run, worker_id)
            run.status = RunStatus.FAILED.value
            run.current_node = "failed"
            run.error_code = error_code.value
            run.result = {
                "errorCode": error_code.value,
                "retryable": retryable,
                "message": safe_message,
            }
            run.lease_owner = None
            run.lease_until = None
            run.completed_at = now
            await self._append_event(
                session,
                run.id,
                "run.failed",
                {
                    "status": RunStatus.FAILED.value,
                    "errorCode": error_code.value,
                    "retryable": retryable,
                },
                now,
            )
            return await self._load_context(session, conversation)

    async def resume(
        self,
        run_id: str,
        user_id: int,
        answer: str,
        message_id: str,
        now: datetime,
    ) -> UpdatedRun:
        normalized_answer = answer.strip()
        if not normalized_answer:
            raise ValueError("Clarification answer must not be blank")
        async with self._sessions() as session, session.begin():
            run = await self._locked_run(session, run_id)
            if run.user_id != user_id:
                raise RunNotFound("Run not found")
            conversation = await self._locked_conversation(session, run.conversation_id)
            if (
                run.status != RunStatus.WAITING_INPUT.value
                and run.resume_value == normalized_answer
            ):
                return UpdatedRun(
                    run=self._to_run_record(run, conversation.knowledge_base_id),
                    context=await self._load_context(session, conversation),
                )
            if run.status != RunStatus.WAITING_INPUT.value:
                raise IdempotencyConflict("Run is not waiting for input")

            session.add(
                AgentMessage(
                    id=message_id,
                    conversation_id=run.conversation_id,
                    run_id=run.id,
                    role=MessageRole.USER.value,
                    content=normalized_answer,
                    citations=[],
                    token_usage={},
                    created_at=now,
                )
            )
            conversation.context_version += 1
            run.status = RunStatus.QUEUED.value
            run.current_node = "resume_queued"
            run.resume_value = normalized_answer
            run.context_version = conversation.context_version
            run.result = {}
            run.lease_owner = None
            run.lease_until = None
            await self._append_event(
                session,
                run.id,
                "run.resumed",
                {"status": RunStatus.QUEUED.value},
                now,
            )
            await session.flush()
            return UpdatedRun(
                run=self._to_run_record(run, conversation.knowledge_base_id),
                context=await self._load_context(session, conversation),
            )

    async def _load_context(
        self,
        session: AsyncSession,
        conversation: AgentConversation,
    ) -> ConversationContext:
        rows = list(
            await session.scalars(
                select(AgentMessage)
                .where(AgentMessage.conversation_id == conversation.id)
                .order_by(AgentMessage.created_at.desc(), AgentMessage.id.desc())
                .limit(self._max_context_messages)
            )
        )
        rows.reverse()
        return ConversationContext(
            conversation_id=conversation.id,
            user_id=conversation.user_id,
            knowledge_base_id=conversation.knowledge_base_id,
            summary=conversation.summary,
            context_version=conversation.context_version,
            messages=[
                ConversationMessage(
                    id=message.id,
                    run_id=message.run_id,
                    role=MessageRole(message.role),
                    content=message.content,
                    citations=message.citations,
                    created_at=message.created_at,
                )
                for message in rows
            ],
        )

    @staticmethod
    async def _locked_conversation(
        session: AsyncSession,
        conversation_id: str,
    ) -> AgentConversation:
        conversation = await session.scalar(
            select(AgentConversation)
            .where(AgentConversation.id == conversation_id)
            .with_for_update()
        )
        if conversation is None:
            raise RunNotFound("Conversation not found")
        return conversation

    @staticmethod
    async def _locked_run(session: AsyncSession, run_id: str) -> AgentRun:
        run = await session.scalar(select(AgentRun).where(AgentRun.id == run_id).with_for_update())
        if run is None:
            raise RunNotFound("Run not found")
        return run

    @staticmethod
    async def _append_event(
        session: AsyncSession,
        run_id: str,
        event_type: str,
        payload: dict[str, object],
        created_at: datetime | None = None,
    ) -> None:
        last_sequence = await session.scalar(
            select(func.coalesce(func.max(AgentRunEvent.sequence), 0)).where(
                AgentRunEvent.run_id == run_id
            )
        )
        values: dict[str, object] = {
            "run_id": run_id,
            "sequence": int(last_sequence or 0) + 1,
            "event_type": event_type,
            "payload": payload,
        }
        if created_at is not None:
            values["created_at"] = created_at
        session.add(AgentRunEvent(**values))

    @staticmethod
    def _assert_conversation_scope(
        conversation: AgentConversation,
        user_id: int,
        knowledge_base_id: int,
    ) -> None:
        if conversation.user_id != user_id or conversation.knowledge_base_id != knowledge_base_id:
            raise ConversationAccessDenied("Conversation scope mismatch")

    @staticmethod
    def _assert_same_request(existing: AgentRun, command: CreateRunCommand) -> None:
        if (
            existing.conversation_id != command.conversation_id
            or existing.question != command.question
            or existing.requested_mode != command.requested_mode.value
        ):
            raise IdempotencyConflict("Idempotency key was reused with a different payload")

    @staticmethod
    def _assert_lease(run: AgentRun, worker_id: str) -> None:
        if run.status != RunStatus.RUNNING.value or run.lease_owner != worker_id:
            raise RunLeaseLost("Worker no longer owns this run")

    @staticmethod
    def _to_run_record(run: AgentRun, knowledge_base_id: int) -> RunRecord:
        return RunRecord(
            id=run.id,
            client_request_id=run.client_request_id,
            conversation_id=run.conversation_id,
            user_id=run.user_id,
            knowledge_base_id=knowledge_base_id,
            question=run.question,
            requested_mode=RetrievalMode(run.requested_mode),
            context_version=run.context_version,
            status=RunStatus(run.status),
            graph_version=run.graph_version,
            current_node=run.current_node,
            query_ids=run.query_ids,
            error_code=run.error_code,
            attempt_count=run.attempt_count,
            lease_owner=run.lease_owner,
            lease_until=run.lease_until,
            resume_value=run.resume_value,
            traceparent=run.traceparent,
            tracestate=run.tracestate,
            result=run.result,
            created_at=run.created_at,
            started_at=run.started_at,
            completed_at=run.completed_at,
        )
