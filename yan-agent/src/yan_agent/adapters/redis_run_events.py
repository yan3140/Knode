import asyncio
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from typing import cast

from redis.asyncio import Redis
from redis.asyncio.client import PubSub
from redis.exceptions import RedisError

from yan_agent.ports.run_events import (
    RunEventListener,
    RunEventNotificationUnavailable,
)


class _RedisRunEventListener:
    def __init__(self, pubsub: PubSub, available: bool) -> None:
        self._pubsub = pubsub
        self._available = available

    async def wait(self, timeout_seconds: float) -> bool:
        if not self._available:
            await asyncio.sleep(timeout_seconds)
            return False
        try:
            message = await self._pubsub.get_message(
                ignore_subscribe_messages=True,
                timeout=timeout_seconds,
            )
        except (OSError, RedisError, TimeoutError):
            self._available = False
            await asyncio.sleep(timeout_seconds)
            return False
        return message is not None


class RedisRunEventNotifier:
    def __init__(
        self,
        client: Redis,
        channel_prefix: str = "yan-agent:run-events:",
    ) -> None:
        self._client = client
        self._channel_prefix = channel_prefix

    async def publish(self, run_id: str) -> None:
        try:
            await cast(
                Awaitable[object],
                self._client.publish(self._channel(run_id), run_id),
            )
        except (OSError, RedisError, TimeoutError) as error:
            raise RunEventNotificationUnavailable("Redis event publish failed") from error

    @asynccontextmanager
    async def listen(self, run_id: str) -> AsyncIterator[RunEventListener]:
        pubsub = self._client.pubsub()
        available = True
        try:
            await pubsub.subscribe(self._channel(run_id))
            async with asyncio.timeout(1):
                while True:
                    confirmation = await pubsub.get_message(
                        ignore_subscribe_messages=False,
                        timeout=0.1,
                    )
                    if confirmation is not None and confirmation.get("type") == "subscribe":
                        break
        except (OSError, RedisError, TimeoutError):
            available = False
        try:
            yield _RedisRunEventListener(pubsub, available)
        finally:
            if available:
                try:
                    await pubsub.unsubscribe(self._channel(run_id))
                except (OSError, RedisError, TimeoutError):
                    pass
            try:
                close = cast(Callable[[], Awaitable[object]], pubsub.aclose)
                await close()
            except (OSError, RedisError, TimeoutError):
                pass

    def _channel(self, run_id: str) -> str:
        return f"{self._channel_prefix}{run_id}"
