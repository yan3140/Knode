import asyncio
from datetime import UTC, datetime, timedelta

import pytest

from yan_agent.adapters.memory.run_event_notifier import InMemoryRunEventNotifier
from yan_agent.adapters.memory.run_repository import InMemoryRunRepository
from yan_agent.application.run_event_notifications import NullRunEventNotifier
from yan_agent.application.run_service import RunService
from yan_agent.domain.runs import CreateRunCommand
from yan_agent.ports.run_events import RunEventNotificationUnavailable


class SequenceIds:
    def __init__(self) -> None:
        self._value = 0

    def __call__(self) -> str:
        self._value += 1
        return f"event-id-{self._value}"


async def enqueue(service: RunService, request_id: str) -> str:
    result = await service.enqueue(
        CreateRunCommand(
            client_request_id=request_id,
            conversation_id=f"conversation-{request_id}",
            user_id=3,
            knowledge_base_id=5,
            question="事件流测试",
        )
    )
    return result.run.id


@pytest.mark.asyncio
async def test_notification_wakes_stream_without_waiting_for_poll_interval() -> None:
    repository = InMemoryRunRepository()
    notifier = InMemoryRunEventNotifier()
    service = RunService(
        repository,
        SequenceIds(),
        event_notifier=notifier,
        event_poll_seconds=10,
    )
    run_id = await enqueue(service, "notification")
    stream = service.watch_events(run_id, user_id=3, after_sequence=1)
    next_event = asyncio.create_task(anext(stream))
    await asyncio.sleep(0)
    now = datetime.now(UTC)

    claimed = await repository.claim_next("worker", now, now + timedelta(seconds=30))
    assert claimed is not None
    await notifier.publish(run_id)
    event = await asyncio.wait_for(next_event, timeout=0.2)
    await stream.aclose()

    assert event is not None
    assert event.event_type == "run.started"


@pytest.mark.asyncio
async def test_lost_notification_is_recovered_by_postgres_polling() -> None:
    repository = InMemoryRunRepository()
    service = RunService(
        repository,
        SequenceIds(),
        event_notifier=NullRunEventNotifier(),
        event_poll_seconds=0.01,
    )
    run_id = await enqueue(service, "poll-fallback")
    stream = service.watch_events(run_id, user_id=3, after_sequence=1)
    next_item = asyncio.create_task(anext(stream))
    await asyncio.sleep(0)
    now = datetime.now(UTC)

    claimed = await repository.claim_next("worker", now, now + timedelta(seconds=30))
    assert claimed is not None
    first = await asyncio.wait_for(next_item, timeout=0.2)
    second = await asyncio.wait_for(anext(stream), timeout=0.2)
    await stream.aclose()

    assert first is None
    assert second is not None
    assert second.event_type == "run.started"


class FailingPublisher(NullRunEventNotifier):
    async def publish(self, run_id: str) -> None:
        raise RunEventNotificationUnavailable("redis unavailable")


@pytest.mark.asyncio
async def test_notification_failure_does_not_rollback_persisted_run() -> None:
    repository = InMemoryRunRepository()
    service = RunService(
        repository,
        SequenceIds(),
        event_notifier=FailingPublisher(),
    )

    run_id = await enqueue(service, "pub-fail")
    events = await service.list_events(run_id, user_id=3)

    assert [event.event_type for event in events] == ["run.queued"]
