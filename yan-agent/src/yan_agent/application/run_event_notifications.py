import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from yan_agent.ports.run_events import (
    RunEventListener,
    RunEventNotificationUnavailable,
    RunEventNotifier,
)


class _PollingListener:
    async def wait(self, timeout_seconds: float) -> bool:
        await asyncio.sleep(timeout_seconds)
        return False


class NullRunEventNotifier:
    async def publish(self, run_id: str) -> None:
        return None

    @asynccontextmanager
    async def listen(self, run_id: str) -> AsyncIterator[RunEventListener]:
        yield _PollingListener()


async def publish_best_effort(notifier: RunEventNotifier, run_id: str) -> None:
    try:
        await notifier.publish(run_id)
    except RunEventNotificationUnavailable:
        return
