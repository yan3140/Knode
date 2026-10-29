import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from yan_agent.ports.run_events import RunEventListener


class _MemoryListener:
    def __init__(self, signal: asyncio.Event) -> None:
        self._signal = signal

    async def wait(self, timeout_seconds: float) -> bool:
        try:
            await asyncio.wait_for(self._signal.wait(), timeout=timeout_seconds)
        except TimeoutError:
            return False
        self._signal.clear()
        return True


class InMemoryRunEventNotifier:
    def __init__(self) -> None:
        self._listeners: dict[str, set[asyncio.Event]] = {}

    async def publish(self, run_id: str) -> None:
        for signal in self._listeners.get(run_id, set()):
            signal.set()

    @asynccontextmanager
    async def listen(self, run_id: str) -> AsyncIterator[RunEventListener]:
        signal = asyncio.Event()
        listeners = self._listeners.setdefault(run_id, set())
        listeners.add(signal)
        try:
            yield _MemoryListener(signal)
        finally:
            listeners.discard(signal)
            if not listeners:
                self._listeners.pop(run_id, None)
