from typing import cast

import pytest
from redis.asyncio import Redis

from yan_agent.adapters.context_cache import RedisContextCache
from yan_agent.domain.runs import ConversationContext


class RecordingRedis:
    def __init__(self) -> None:
        self.eval_args: tuple[object, ...] = ()

    async def eval(self, *args: object) -> int:
        self.eval_args = args
        return 1


@pytest.mark.asyncio
async def test_context_cache_ttl_has_configured_jitter() -> None:
    redis = RecordingRedis()
    cache = RedisContextCache(
        cast(Redis, redis),
        ttl_seconds=100,
        ttl_jitter_ratio=0.1,
        random_value=lambda: 1.0,
    )
    context = ConversationContext(
        conversation_id="conversation-jitter",
        user_id=3,
        knowledge_base_id=5,
        summary=None,
        context_version=1,
        messages=[],
    )

    await cache.put_if_newer(context)

    assert redis.eval_args[-1] == 110
