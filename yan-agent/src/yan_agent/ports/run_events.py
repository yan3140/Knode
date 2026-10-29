from contextlib import AbstractAsyncContextManager
from typing import Protocol


class RunEventNotificationUnavailable(RuntimeError):
    pass


class RunEventListener(Protocol):
    async def wait(self, timeout_seconds: float) -> bool: ...


class RunEventNotifier(Protocol):
    async def publish(self, run_id: str) -> None: ...

    def listen(self, run_id: str) -> AbstractAsyncContextManager[RunEventListener]: ...
