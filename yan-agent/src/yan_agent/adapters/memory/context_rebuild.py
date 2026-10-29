import asyncio
import time


class InMemoryContextRebuildCoordinator:
    def __init__(self) -> None:
        self._guard = asyncio.Lock()
        self._leases: dict[str, tuple[str, float]] = {}
        self._sequence = 0

    async def acquire(self, conversation_id: str, lease_seconds: int) -> str | None:
        async with self._guard:
            current = self._leases.get(conversation_id)
            now = time.monotonic()
            if current is not None and current[1] > now:
                return None
            self._sequence += 1
            token = f"rebuild-{self._sequence}"
            self._leases[conversation_id] = (token, now + lease_seconds)
            return token

    async def release(self, conversation_id: str, token: str) -> None:
        async with self._guard:
            current = self._leases.get(conversation_id)
            if current is not None and current[0] == token:
                self._leases.pop(conversation_id, None)
