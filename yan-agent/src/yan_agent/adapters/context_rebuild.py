import secrets
from collections.abc import Awaitable
from typing import cast

from redis.asyncio import Redis
from redis.exceptions import RedisError

from yan_agent.ports.context_rebuild import ContextRebuildCoordinatorUnavailable

_RELEASE_IF_OWNER = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
"""


class RedisContextRebuildCoordinator:
    def __init__(
        self,
        client: Redis,
        key_prefix: str = "yan-agent:context-rebuild:",
    ) -> None:
        self._client = client
        self._key_prefix = key_prefix

    async def acquire(self, conversation_id: str, lease_seconds: int) -> str | None:
        token = secrets.token_urlsafe(24)
        try:
            acquired = await self._client.set(
                self._key(conversation_id),
                token,
                ex=lease_seconds,
                nx=True,
            )
        except (OSError, RedisError, TimeoutError) as error:
            raise ContextRebuildCoordinatorUnavailable(
                "Redis rebuild lock acquisition failed"
            ) from error
        return token if acquired else None

    async def release(self, conversation_id: str, token: str) -> None:
        try:
            await cast(
                Awaitable[object],
                self._client.eval(
                    _RELEASE_IF_OWNER,
                    1,
                    self._key(conversation_id),
                    token,
                ),
            )
        except (OSError, RedisError, TimeoutError) as error:
            raise ContextRebuildCoordinatorUnavailable(
                "Redis rebuild lock release failed"
            ) from error

    def _key(self, conversation_id: str) -> str:
        return f"{self._key_prefix}{conversation_id}"
