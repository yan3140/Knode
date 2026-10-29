import asyncio
from datetime import UTC, datetime

from yan_agent.domain.errors import AgentErrorCode
from yan_agent.domain.qa import AgentResult
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


class InMemoryRunRepository:
    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._runs: dict[str, RunRecord] = {}
        self._request_index: dict[tuple[int, str], str] = {}
        self._contexts: dict[str, ConversationContext] = {}
        self._events: dict[str, list[RunEvent]] = {}

    async def enqueue(
        self,
        command: CreateRunCommand,
        run_id: str,
        message_id: str,
    ) -> EnqueuedRun:
        async with self._lock:
            existing_id = self._request_index.get((command.user_id, command.client_request_id))
            if existing_id is not None:
                existing = self._runs[existing_id]
                self._assert_same_request(existing, command)
                return EnqueuedRun(
                    run=existing,
                    context=self._contexts[existing.conversation_id],
                    created=False,
                )

            context = self._contexts.get(command.conversation_id)
            if context is None:
                context = ConversationContext(
                    conversation_id=command.conversation_id,
                    user_id=command.user_id,
                    knowledge_base_id=command.knowledge_base_id,
                    summary=None,
                    context_version=0,
                    messages=[],
                )
            self._assert_context_access(context, command.user_id, command.knowledge_base_id)

            now = datetime.now(UTC)
            run = RunRecord(
                id=run_id,
                client_request_id=command.client_request_id,
                conversation_id=command.conversation_id,
                user_id=command.user_id,
                knowledge_base_id=command.knowledge_base_id,
                question=command.question,
                requested_mode=command.requested_mode,
                context_version=context.context_version + 1,
                status=RunStatus.QUEUED,
                graph_version=GRAPH_VERSION,
                traceparent=command.traceparent,
                tracestate=command.tracestate,
                created_at=now,
            )
            message = ConversationMessage(
                id=message_id,
                run_id=run_id,
                role=MessageRole.USER,
                content=command.question,
                created_at=now,
            )
            context = context.model_copy(
                update={
                    "context_version": context.context_version + 1,
                    "messages": [*context.messages, message],
                }
            )
            self._runs[run_id] = run
            self._events[run_id] = []
            self._append_event(
                run_id,
                "run.queued",
                {"status": RunStatus.QUEUED.value},
                now,
            )
            self._request_index[(command.user_id, command.client_request_id)] = run_id
            self._contexts[command.conversation_id] = context
            return EnqueuedRun(run=run, context=context, created=True)

    async def load_context(self, conversation_id: str, user_id: int) -> ConversationContext:
        context = self._contexts.get(conversation_id)
        if context is None:
            raise RunNotFound("Conversation not found")
        if context.user_id != user_id:
            raise ConversationAccessDenied("Conversation belongs to another user")
        return context

    async def get_run(self, run_id: str, user_id: int) -> RunRecord:
        run = self._runs.get(run_id)
        if run is None or run.user_id != user_id:
            raise RunNotFound("Run not found")
        return run

    async def list_events(
        self,
        run_id: str,
        user_id: int,
        after_sequence: int = 0,
    ) -> list[RunEvent]:
        run = self._runs.get(run_id)
        if run is None or run.user_id != user_id:
            raise RunNotFound("Run not found")
        return [event for event in self._events.get(run_id, []) if event.sequence > after_sequence]

    async def claim_next(
        self,
        worker_id: str,
        now: datetime,
        lease_until: datetime,
    ) -> RunRecord | None:
        async with self._lock:
            candidates = [
                run
                for run in self._runs.values()
                if run.status is RunStatus.QUEUED
                or (
                    run.status is RunStatus.RUNNING
                    and run.lease_until is not None
                    and run.lease_until <= now
                )
            ]
            if not candidates:
                return None
            current = min(candidates, key=lambda item: (item.created_at, item.id))
            claimed = current.model_copy(
                update={
                    "status": RunStatus.RUNNING,
                    "current_node": "claimed",
                    "attempt_count": current.attempt_count + 1,
                    "lease_owner": worker_id,
                    "lease_until": lease_until,
                    "started_at": current.started_at or now,
                }
            )
            self._runs[current.id] = claimed
            self._append_event(
                current.id,
                "run.started",
                {
                    "status": RunStatus.RUNNING.value,
                    "attemptCount": claimed.attempt_count,
                },
                now,
            )
            return claimed

    async def complete(
        self,
        run_id: str,
        worker_id: str,
        result: "AgentResult",
        message_id: str,
        now: datetime,
    ) -> ConversationContext:
        async with self._lock:
            run = self._runs.get(run_id)
            if run is None:
                raise RunNotFound("Run not found")
            if run.status in {
                RunStatus.SUCCEEDED,
                RunStatus.INSUFFICIENT_EVIDENCE,
                RunStatus.FAILED,
                RunStatus.CANCELLED,
            }:
                return self._contexts[run.conversation_id]
            if run.status is not RunStatus.RUNNING or run.lease_owner != worker_id:
                raise RunLeaseLost("Worker no longer owns this run")

            context = self._contexts[run.conversation_id]
            message = ConversationMessage(
                id=message_id,
                run_id=run.id,
                role=MessageRole.ASSISTANT,
                content=result.answer,
                citations=[item.model_dump(mode="json") for item in result.citations],
                created_at=now,
            )
            context = context.model_copy(
                update={
                    "context_version": context.context_version + 1,
                    "messages": [*context.messages, message],
                }
            )
            terminal_status = RunStatus(result.status.value)
            completed = run.model_copy(
                update={
                    "status": terminal_status,
                    "current_node": "completed",
                    "query_ids": result.query_ids,
                    "lease_owner": None,
                    "lease_until": None,
                    "result": result.model_dump(mode="json"),
                    "completed_at": now,
                }
            )
            self._runs[run.id] = completed
            self._contexts[run.conversation_id] = context
            for event_type, payload in completion_event_specs(result):
                self._append_event(run.id, event_type, payload, now)
            return context

    async def renew_lease(
        self,
        run_id: str,
        worker_id: str,
        now: datetime,
        lease_until: datetime,
    ) -> bool:
        async with self._lock:
            run = self._runs.get(run_id)
            if (
                run is None
                or run.status is not RunStatus.RUNNING
                or run.lease_owner != worker_id
                or run.lease_until is None
                or run.lease_until <= now
            ):
                return False
            self._runs[run.id] = run.model_copy(update={"lease_until": lease_until})
            return True

    async def mark_waiting(
        self,
        run_id: str,
        worker_id: str,
        result: "AgentResult",
    ) -> ConversationContext:
        async with self._lock:
            run = self._runs.get(run_id)
            if run is None:
                raise RunNotFound("Run not found")
            if run.status is not RunStatus.RUNNING or run.lease_owner != worker_id:
                raise RunLeaseLost("Worker no longer owns this run")
            waiting = run.model_copy(
                update={
                    "status": RunStatus.WAITING_INPUT,
                    "current_node": "interrupt_for_clarification",
                    "query_ids": result.query_ids,
                    "lease_owner": None,
                    "lease_until": None,
                    "result": result.model_dump(mode="json"),
                }
            )
            self._runs[run.id] = waiting
            self._append_event(
                run.id,
                "run.waiting_input",
                {
                    "status": RunStatus.WAITING_INPUT.value,
                    "queryIds": result.query_ids,
                },
                datetime.now(UTC),
            )
            return self._contexts[run.conversation_id]

    async def fail(
        self,
        run_id: str,
        worker_id: str,
        error_code: AgentErrorCode,
        retryable: bool,
        safe_message: str,
        now: datetime,
    ) -> ConversationContext:
        async with self._lock:
            run = self._runs.get(run_id)
            if run is None:
                raise RunNotFound("Run not found")
            if run.status in {
                RunStatus.SUCCEEDED,
                RunStatus.INSUFFICIENT_EVIDENCE,
                RunStatus.FAILED,
                RunStatus.CANCELLED,
            }:
                return self._contexts[run.conversation_id]
            if run.status is not RunStatus.RUNNING or run.lease_owner != worker_id:
                raise RunLeaseLost("Worker no longer owns this run")
            failed = run.model_copy(
                update={
                    "status": RunStatus.FAILED,
                    "current_node": "failed",
                    "error_code": error_code.value,
                    "lease_owner": None,
                    "lease_until": None,
                    "result": {
                        "errorCode": error_code.value,
                        "retryable": retryable,
                        "message": safe_message,
                    },
                    "completed_at": now,
                }
            )
            self._runs[run.id] = failed
            self._append_event(
                run.id,
                "run.failed",
                {
                    "status": RunStatus.FAILED.value,
                    "errorCode": error_code.value,
                    "retryable": retryable,
                },
                now,
            )
            return self._contexts[run.conversation_id]

    async def resume(
        self,
        run_id: str,
        user_id: int,
        answer: str,
        message_id: str,
        now: datetime,
    ) -> UpdatedRun:
        normalized_answer = answer.strip()
        async with self._lock:
            run = self._runs.get(run_id)
            if run is None or run.user_id != user_id:
                raise RunNotFound("Run not found")
            if run.status is not RunStatus.WAITING_INPUT and run.resume_value == normalized_answer:
                return UpdatedRun(run=run, context=self._contexts[run.conversation_id])
            if run.status is not RunStatus.WAITING_INPUT:
                raise IdempotencyConflict("Run is not waiting for input")
            if not normalized_answer:
                raise ValueError("Clarification answer must not be blank")

            context = self._contexts[run.conversation_id]
            message = ConversationMessage(
                id=message_id,
                run_id=run.id,
                role=MessageRole.USER,
                content=normalized_answer,
                created_at=now,
            )
            context = context.model_copy(
                update={
                    "context_version": context.context_version + 1,
                    "messages": [*context.messages, message],
                }
            )
            resumed = run.model_copy(
                update={
                    "status": RunStatus.QUEUED,
                    "current_node": "resume_queued",
                    "resume_value": normalized_answer,
                    "context_version": context.context_version,
                    "result": {},
                    "lease_owner": None,
                    "lease_until": None,
                }
            )
            self._contexts[run.conversation_id] = context
            self._runs[run.id] = resumed
            self._append_event(
                run.id,
                "run.resumed",
                {"status": RunStatus.QUEUED.value},
                now,
            )
            return UpdatedRun(run=resumed, context=context)

    def _append_event(
        self,
        run_id: str,
        event_type: str,
        payload: dict[str, object],
        created_at: datetime,
    ) -> None:
        events = self._events.setdefault(run_id, [])
        events.append(
            RunEvent(
                sequence=len(events) + 1,
                event_type=event_type,
                payload=payload,
                created_at=created_at,
            )
        )

    @staticmethod
    def _assert_same_request(existing: RunRecord, command: CreateRunCommand) -> None:
        if (
            existing.conversation_id != command.conversation_id
            or existing.knowledge_base_id != command.knowledge_base_id
            or existing.question != command.question
            or existing.requested_mode != command.requested_mode
        ):
            raise IdempotencyConflict("Idempotency key was reused with a different payload")

    @staticmethod
    def _assert_context_access(
        context: ConversationContext,
        user_id: int,
        knowledge_base_id: int,
    ) -> None:
        if context.user_id != user_id or context.knowledge_base_id != knowledge_base_id:
            raise ConversationAccessDenied("Conversation scope mismatch")
