from collections.abc import AsyncIterator, Callable
from datetime import UTC, datetime

from yan_agent.application.context_manager import ContextManager
from yan_agent.application.run_event_notifications import (
    NullRunEventNotifier,
    publish_best_effort,
)
from yan_agent.domain.runs import (
    ConversationContext,
    CreateRunCommand,
    EnqueuedRun,
    RunEvent,
    RunRecord,
)
from yan_agent.ports.run_events import RunEventNotifier
from yan_agent.ports.runs import RunRepository


class RunService:
    def __init__(
        self,
        repository: RunRepository,
        new_id: Callable[[], str],
        context_manager: ContextManager | None = None,
        event_notifier: RunEventNotifier | None = None,
        event_poll_seconds: float = 5.0,
    ) -> None:
        self._repository = repository
        self._new_id = new_id
        self._context_manager = context_manager
        self._event_notifier = event_notifier or NullRunEventNotifier()
        self._event_poll_seconds = event_poll_seconds

    async def enqueue(self, command: CreateRunCommand) -> EnqueuedRun:
        outcome = await self._repository.enqueue(
            command=command,
            run_id=self._new_id(),
            message_id=self._new_id(),
        )
        if outcome.created and self._context_manager is not None:
            await self._context_manager.publish_committed(outcome.context)
        if outcome.created:
            await publish_best_effort(self._event_notifier, outcome.run.id)
        return outcome

    async def load_context(self, conversation_id: str, user_id: int) -> ConversationContext:
        if self._context_manager is not None:
            return await self._context_manager.load(conversation_id, user_id)
        return await self._repository.load_context(conversation_id, user_id)

    async def get_run(self, run_id: str, user_id: int) -> RunRecord:
        return await self._repository.get_run(run_id, user_id)

    async def list_events(
        self,
        run_id: str,
        user_id: int,
        after_sequence: int = 0,
    ) -> list[RunEvent]:
        return await self._repository.list_events(run_id, user_id, after_sequence)

    async def watch_events(
        self,
        run_id: str,
        user_id: int,
        after_sequence: int = 0,
    ) -> AsyncIterator[RunEvent | None]:
        last_sequence = after_sequence
        async with self._event_notifier.listen(run_id) as listener:
            while True:
                events = await self._repository.list_events(run_id, user_id, last_sequence)
                for event in events:
                    last_sequence = event.sequence
                    yield event
                    if event.event_type in {"run.completed", "run.failed", "run.cancelled"}:
                        return
                if not await listener.wait(self._event_poll_seconds):
                    yield None

    async def resume(self, run_id: str, user_id: int, answer: str) -> RunRecord:
        outcome = await self._repository.resume(
            run_id=run_id,
            user_id=user_id,
            answer=answer,
            message_id=self._new_id(),
            now=datetime.now(UTC),
        )
        if self._context_manager is not None:
            await self._context_manager.publish_committed(outcome.context)
        await publish_best_effort(self._event_notifier, outcome.run.id)
        return outcome.run
