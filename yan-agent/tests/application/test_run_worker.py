from datetime import UTC, datetime, timedelta

import pytest

from yan_agent.adapters.memory.context_cache import InMemoryContextCache
from yan_agent.adapters.memory.run_event_notifier import InMemoryRunEventNotifier
from yan_agent.adapters.memory.run_repository import InMemoryRunRepository
from yan_agent.application.context_manager import ContextManager
from yan_agent.application.run_service import RunService
from yan_agent.application.run_worker import RunWorker
from yan_agent.domain.errors import ModelTimeoutError
from yan_agent.domain.qa import AgentInvocation, AgentResult, AgentStatus
from yan_agent.domain.runs import CreateRunCommand, RunStatus


class SequenceIds:
    def __init__(self) -> None:
        self._value = 0

    def __call__(self) -> str:
        self._value += 1
        return f"id-{self._value}"


class SuccessfulAgent:
    def __init__(self) -> None:
        self.invocations: list[AgentInvocation] = []

    async def invoke(self, invocation: AgentInvocation) -> AgentResult:
        self.invocations.append(invocation)
        return AgentResult(
            run_id=invocation.run_id,
            status=AgentStatus.SUCCEEDED,
            answer="已根据证据回答。",
            citations=[],
            query_ids=[9],
        )

    async def resume(self, run_id: str, answer: str) -> AgentResult:
        raise AssertionError("new run must not resume a checkpoint")


class ClarifyingAgent:
    def __init__(self) -> None:
        self.resume_answers: list[str] = []

    async def invoke(self, invocation: AgentInvocation) -> AgentResult:
        return AgentResult(
            run_id=invocation.run_id,
            status=AgentStatus.CLARIFICATION_REQUIRED,
            answer="你指的是哪个服务？",
            citations=[],
            query_ids=[],
        )

    async def resume(self, run_id: str, answer: str) -> AgentResult:
        self.resume_answers.append(answer)
        return AgentResult(
            run_id=run_id,
            status=AgentStatus.SUCCEEDED,
            answer="补充信息后已回答。",
            citations=[],
            query_ids=[10],
        )


@pytest.mark.asyncio
async def test_worker_claims_run_and_commits_one_assistant_message() -> None:
    repository = InMemoryRunRepository()
    contexts = ContextManager(repository, InMemoryContextCache())
    ids = SequenceIds()
    service = RunService(repository, ids, contexts)
    enqueued = await service.enqueue(
        CreateRunCommand(
            client_request_id="request-1",
            conversation_id="conversation-1",
            user_id=3,
            knowledge_base_id=5,
            question="线程池队列满了怎么办？",
        )
    )
    agent = SuccessfulAgent()
    worker = RunWorker(
        repository=repository,
        contexts=contexts,
        agent=agent,
        new_id=ids,
        clock=lambda: datetime(2026, 8, 7, 14, tzinfo=UTC),
    )

    assert await worker.run_once("worker-1") is True
    assert await worker.run_once("worker-1") is False
    run = await service.get_run(enqueued.run.id, user_id=3)
    context = await service.load_context("conversation-1", user_id=3)

    assert run.status is RunStatus.SUCCEEDED
    assert run.query_ids == [9]
    assert agent.invocations[0].conversation_context is not None
    assert agent.invocations[0].context_version == 1
    assert '"contextVersion":1' in agent.invocations[0].conversation_context
    assert context.context_version == 2
    assert [message.content for message in context.messages] == [
        "线程池队列满了怎么办？",
        "已根据证据回答。",
    ]


@pytest.mark.asyncio
async def test_expired_worker_lease_can_be_reclaimed_after_crash() -> None:
    repository = InMemoryRunRepository()
    ids = SequenceIds()
    service = RunService(repository, ids)
    await service.enqueue(
        CreateRunCommand(
            client_request_id="request-crash",
            conversation_id="conversation-crash",
            user_id=3,
            knowledge_base_id=5,
            question="可恢复的问题",
        )
    )
    now = datetime(2026, 8, 7, 14, tzinfo=UTC)

    first = await repository.claim_next("worker-dead", now, now + timedelta(seconds=10))
    too_early = await repository.claim_next(
        "worker-new",
        now + timedelta(seconds=5),
        now + timedelta(seconds=15),
    )
    recovered = await repository.claim_next(
        "worker-new",
        now + timedelta(seconds=11),
        now + timedelta(seconds=21),
    )

    assert first is not None
    assert too_early is None
    assert recovered is not None
    assert recovered.lease_owner == "worker-new"
    assert recovered.attempt_count == 2


@pytest.mark.asyncio
async def test_waiting_run_resumes_from_checkpoint_after_user_input() -> None:
    repository = InMemoryRunRepository()
    contexts = ContextManager(repository, InMemoryContextCache())
    ids = SequenceIds()
    service = RunService(repository, ids, contexts)
    enqueued = await service.enqueue(
        CreateRunCommand(
            client_request_id="request-resume",
            conversation_id="conversation-resume",
            user_id=3,
            knowledge_base_id=5,
            question="这个怎么配？",
        )
    )
    agent = ClarifyingAgent()
    worker = RunWorker(
        repository=repository,
        contexts=contexts,
        agent=agent,
        new_id=ids,
        clock=lambda: datetime(2026, 8, 7, 14, tzinfo=UTC),
    )

    await worker.run_once("worker-1")
    waiting = await service.get_run(enqueued.run.id, user_id=3)
    await service.resume(enqueued.run.id, user_id=3, answer="线程池服务")
    await worker.run_once("worker-2")
    completed = await service.get_run(enqueued.run.id, user_id=3)
    context = await service.load_context("conversation-resume", user_id=3)

    assert waiting.status is RunStatus.WAITING_INPUT
    assert completed.status is RunStatus.SUCCEEDED
    assert agent.resume_answers == ["线程池服务"]
    assert context.context_version == 3
    assert [message.content for message in context.messages] == [
        "这个怎么配？",
        "线程池服务",
        "补充信息后已回答。",
    ]


@pytest.mark.asyncio
async def test_lease_heartbeat_prevents_live_run_from_being_reclaimed() -> None:
    repository = InMemoryRunRepository()
    service = RunService(repository, SequenceIds())
    await service.enqueue(
        CreateRunCommand(
            client_request_id="request-heartbeat",
            conversation_id="conversation-heartbeat",
            user_id=3,
            knowledge_base_id=5,
            question="长时间运行的问题",
        )
    )
    now = datetime(2026, 8, 7, 14, tzinfo=UTC)
    claimed = await repository.claim_next("worker-live", now, now + timedelta(seconds=10))
    assert claimed is not None

    renewed = await repository.renew_lease(
        claimed.id,
        "worker-live",
        now + timedelta(seconds=8),
        now + timedelta(seconds=18),
    )
    stolen = await repository.claim_next(
        "worker-other",
        now + timedelta(seconds=11),
        now + timedelta(seconds=21),
    )

    assert renewed is True
    assert stolen is None


class TimeoutAgent(SuccessfulAgent):
    async def invoke(self, invocation: AgentInvocation) -> AgentResult:
        raise ModelTimeoutError()


class DegradedAgent(SuccessfulAgent):
    async def invoke(self, invocation: AgentInvocation) -> AgentResult:
        return AgentResult(
            run_id=invocation.run_id,
            status=AgentStatus.SUCCEEDED,
            answer="降级检索后回答。[1]",
            citations=[],
            query_ids=[41, 42],
            degraded=True,
            degradation_reasons=["VECTOR_UNAVAILABLE"],
            security_flags=["PROMPT_INJECTION_DETECTED"],
            grounding_repaired=True,
            model_calls=8,
        )


@pytest.mark.asyncio
async def test_model_failure_is_terminal_and_auditable() -> None:
    repository = InMemoryRunRepository()
    contexts = ContextManager(repository, InMemoryContextCache())
    ids = SequenceIds()
    service = RunService(repository, ids, contexts)
    enqueued = await service.enqueue(
        CreateRunCommand(
            client_request_id="request-timeout",
            conversation_id="conversation-timeout",
            user_id=3,
            knowledge_base_id=5,
            question="会超时的问题",
        )
    )
    worker = RunWorker(
        repository=repository,
        contexts=contexts,
        agent=TimeoutAgent(),
        new_id=ids,
        clock=lambda: datetime(2026, 8, 7, 14, tzinfo=UTC),
    )

    assert await worker.run_once("worker-1") is True
    run = await service.get_run(enqueued.run.id, user_id=3)
    events = await service.list_events(enqueued.run.id, user_id=3)

    assert run.status is RunStatus.FAILED
    assert run.error_code == "MODEL_TIMEOUT"
    failed = next(event for event in events if event.event_type == "run.failed")
    assert failed.payload == {
        "status": "FAILED",
        "errorCode": "MODEL_TIMEOUT",
        "retryable": True,
    }


@pytest.mark.asyncio
async def test_retrieval_degradation_is_recorded_in_completion_event() -> None:
    repository = InMemoryRunRepository()
    contexts = ContextManager(repository, InMemoryContextCache())
    ids = SequenceIds()
    service = RunService(repository, ids, contexts)
    enqueued = await service.enqueue(
        CreateRunCommand(
            client_request_id="request-degraded",
            conversation_id="conversation-degraded",
            user_id=3,
            knowledge_base_id=5,
            question="降级检索的问题",
        )
    )
    worker = RunWorker(
        repository=repository,
        contexts=contexts,
        agent=DegradedAgent(),
        new_id=ids,
        clock=lambda: datetime(2026, 8, 7, 14, tzinfo=UTC),
    )

    await worker.run_once("worker-1")
    events = await service.list_events(enqueued.run.id, user_id=3)
    completed = next(event for event in events if event.event_type == "run.completed")

    assert completed.payload["queryIds"] == [41, 42]
    assert completed.payload["degradationReasons"] == ["VECTOR_UNAVAILABLE"]
    assert completed.payload["securityFlags"] == ["PROMPT_INJECTION_DETECTED"]
    assert completed.payload["groundingRepaired"] is True


@pytest.mark.asyncio
async def test_disconnected_event_stream_does_not_cancel_worker_run() -> None:
    repository = InMemoryRunRepository()
    contexts = ContextManager(repository, InMemoryContextCache())
    notifier = InMemoryRunEventNotifier()
    ids = SequenceIds()
    service = RunService(repository, ids, contexts, event_notifier=notifier)
    enqueued = await service.enqueue(
        CreateRunCommand(
            client_request_id="request-disconnect",
            conversation_id="conversation-disconnect",
            user_id=3,
            knowledge_base_id=5,
            question="断开事件流后继续执行",
        )
    )
    stream = service.watch_events(enqueued.run.id, user_id=3)
    queued = await anext(stream)
    await stream.aclose()
    worker = RunWorker(
        repository=repository,
        contexts=contexts,
        agent=SuccessfulAgent(),
        new_id=ids,
        clock=lambda: datetime(2026, 8, 8, 10, tzinfo=UTC),
        event_notifier=notifier,
    )

    await worker.run_once("worker-disconnect")
    completed = await service.get_run(enqueued.run.id, user_id=3)

    assert queued is not None
    assert queued.event_type == "run.queued"
    assert completed.status is RunStatus.SUCCEEDED
