from typing import Protocol


class ContextRebuildCoordinatorUnavailable(RuntimeError):
    pass


class ContextRebuildCoordinator(Protocol):
    async def acquire(self, conversation_id: str, lease_seconds: int) -> str | None: ...

    async def release(self, conversation_id: str, token: str) -> None: ...
