import os

import pytest
from redis.asyncio import Redis

from yan_agent.adapters.context_cache import RedisContextCache
from yan_agent.adapters.context_rebuild import RedisContextRebuildCoordinator
from yan_agent.adapters.redis_run_events import RedisRunEventNotifier
from yan_agent.domain.runs import ConversationContext
from yan_agent.ids import new_ulid

TEST_REDIS_URL = os.getenv("YAN_AGENT_TEST_REDIS_URL") or os.getenv("YAN_AGENT_REDIS_URL")
pytestmark = pytest.mark.skipif(TEST_REDIS_URL is None, reason="Redis integration URL is not set")


@pytest.mark.asyncio
async def test_redis_rejects_delayed_stale_context_write() -> None:
    assert TEST_REDIS_URL is not None
    client = Redis.from_url(TEST_REDIS_URL, decode_responses=True)
    cache = RedisContextCache(client, ttl_seconds=60, key_prefix=f"test:{new_ulid()}:")
    context = ConversationContext(
        conversation_id=new_ulid(),
        user_id=3,
        knowledge_base_id=5,
        summary=None,
        context_version=1,
        messages=[],
    )
    newer = context.model_copy(update={"context_version": 2})
    try:
        accepted = await cache.put_if_newer(newer)
        rejected = await cache.put_if_newer(context)
        loaded = await cache.get(context.conversation_id)
    finally:
        await cache.delete(context.conversation_id)
        await client.aclose()

    assert accepted is True
    assert rejected is False
    assert loaded is not None
    assert loaded.context_version == 2


@pytest.mark.asyncio
async def test_redis_pubsub_wakes_all_run_event_listeners() -> None:
    assert TEST_REDIS_URL is not None
    client = Redis.from_url(TEST_REDIS_URL, decode_responses=True)
    notifier = RedisRunEventNotifier(client, channel_prefix=f"test:{new_ulid()}:")
    run_id = new_ulid()
    try:
        async with notifier.listen(run_id) as first, notifier.listen(run_id) as second:
            await notifier.publish(run_id)

            assert await first.wait(1) is True
            assert await second.wait(1) is True
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_redis_rebuild_lease_is_exclusive_and_releasable() -> None:
    assert TEST_REDIS_URL is not None
    client = Redis.from_url(TEST_REDIS_URL, decode_responses=True)
    coordinator = RedisContextRebuildCoordinator(
        client,
        key_prefix=f"test:{new_ulid()}:",
    )
    conversation_id = new_ulid()
    try:
        first = await coordinator.acquire(conversation_id, lease_seconds=5)
        second = await coordinator.acquire(conversation_id, lease_seconds=5)
        assert first is not None
        assert second is None

        await coordinator.release(conversation_id, first)
        third = await coordinator.acquire(conversation_id, lease_seconds=5)
        assert third is not None
        await coordinator.release(conversation_id, third)
    finally:
        await client.aclose()
