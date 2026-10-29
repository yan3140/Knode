import random
from collections.abc import Awaitable, Callable
from typing import cast

from pydantic import ValidationError
from redis.asyncio import Redis
from redis.exceptions import RedisError

from yan_agent.domain.runs import ConversationContext
from yan_agent.ports.context_cache import ContextCacheUnavailable

_PUT_IF_NEWER = """
local current = redis.call('GET', KEYS[1])
if current then
  local decoded = cjson.decode(current)
  local current_version = tonumber(decoded['context_version'])
  local incoming_version = tonumber(ARGV[2])
  if current_version >= incoming_version then
    return 0
  end
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
return 1
"""


class RedisContextCache:
    def __init__(
        self,
        client: Redis,
        ttl_seconds: int,
        key_prefix: str = "yan-agent:context:",
        ttl_jitter_ratio: float = 0.1,
        random_value: Callable[[], float] = random.random,
    ) -> None:
        if not 0 <= ttl_jitter_ratio <= 0.5:
            raise ValueError("ttl_jitter_ratio must be between 0 and 0.5")
        self._client = client
        self._ttl_seconds = ttl_seconds
        self._key_prefix = key_prefix
        self._ttl_jitter_ratio = ttl_jitter_ratio
        self._random_value = random_value

    async def get(self, conversation_id: str) -> ConversationContext | None:
        try:
            payload = await self._client.get(self._key(conversation_id))
        except (OSError, RedisError, TimeoutError) as error:
            raise ContextCacheUnavailable("Redis context read failed") from error
        if payload is None:
            return None
        try:
            return ConversationContext.model_validate_json(payload)
        except (ValueError, ValidationError) as error:
            try:
                await self._client.delete(self._key(conversation_id))
            except (OSError, RedisError, TimeoutError):
                pass
            raise ContextCacheUnavailable("Redis context value is invalid") from error

    async def put_if_newer(self, context: ConversationContext) -> bool:
        try:
            result = await cast(
                Awaitable[object],
                self._client.eval(
                    _PUT_IF_NEWER,
                    1,
                    self._key(context.conversation_id),
                    context.model_dump_json(),
                    context.context_version,
                    self._jittered_ttl_seconds(),
                ),
            )
        except (OSError, RedisError, TimeoutError) as error:
            raise ContextCacheUnavailable("Redis context write failed") from error
        return bool(result)

    async def delete(self, conversation_id: str) -> None:
        try:
            await self._client.delete(self._key(conversation_id))
        except (OSError, RedisError, TimeoutError) as error:
            raise ContextCacheUnavailable("Redis context delete failed") from error

    def _key(self, conversation_id: str) -> str:
        return f"{self._key_prefix}{conversation_id}"

    def _jittered_ttl_seconds(self) -> int:
        offset = (self._random_value() * 2) - 1
        return max(1, round(self._ttl_seconds * (1 + offset * self._ttl_jitter_ratio)))
