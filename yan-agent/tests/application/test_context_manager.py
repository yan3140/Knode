import asyncio

import pytest

from yan_agent.adapters.memory.context_cache import InMemoryContextCache
from yan_agent.adapters.memory.context_rebuild import InMemoryContextRebuildCoordinator
from yan_agent.adapters.memory.run_repository import InMemoryRunRepository
from yan_agent.application.context_manager import ContextManager
from yan_agent.application.run_service import RunService
from yan_agent.domain.qa import RetrievalMode
from yan_agent.domain.runs import ConversationContext, CreateRunCommand
from yan_agent.ports.context_cache import ContextCacheUnavailable


class SequenceIds:
    def __init__(self) -> None:
        self._value = 0

    def __call__(self) -> str:
        self._value += 1
        return f"id-{self._value}"


class UnavailableCache:
    async def get(self, conversation_id: str) -> ConversationContext | None:
        raise ContextCacheUnavailable("redis unavailable")

    async def put_if_newer(self, context: ConversationContext) -> bool:
        raise ContextCacheUnavailable("redis unavailable")

    async def delete(self, conversation_id: str) -> None:
        raise ContextCacheUnavailable("redis unavailable")


class DelayedContextRepository:
    def __init__(self, repository: InMemoryRunRepository, delay: float = 0.02) -> None:
        self._repository = repository
        self._delay = delay
        self.load_count = 0
        self.active_loads = 0
        self.max_active_loads = 0

    async def load_context(self, conversation_id: str, user_id: int) -> ConversationContext:
        self.load_count += 1
        self.active_loads += 1
        self.max_active_loads = max(self.max_active_loads, self.active_loads)
        try:
            await asyncio.sleep(self._delay)
            return await self._repository.load_context(conversation_id, user_id)
        finally:
            self.active_loads -= 1


@pytest.mark.asyncio
async def test_redis_failure_falls_back_to_postgres_context() -> None:
    repository = InMemoryRunRepository()
    ids = SequenceIds()
    writer = RunService(repository=repository, new_id=ids)
    await writer.enqueue(
        CreateRunCommand(
            client_request_id="request-1",
            conversation_id="conversation-1",
            user_id=3,
            knowledge_base_id=5,
            question="数据库中的消息",
            requested_mode=RetrievalMode.AUTO,
        )
    )
    contexts = ContextManager(repository=repository, cache=UnavailableCache())

    context = await contexts.load("conversation-1", user_id=3)

    assert context.context_version == 1
    assert context.messages[0].content == "数据库中的消息"


@pytest.mark.asyncio
async def test_older_context_version_cannot_overwrite_new_cache_value() -> None:
    cache = InMemoryContextCache()
    base = ConversationContext(
        conversation_id="conversation-1",
        user_id=3,
        knowledge_base_id=5,
        summary=None,
        context_version=1,
        messages=[],
    )
    newer = base.model_copy(update={"context_version": 2})

    assert await cache.put_if_newer(newer) is True
    assert await cache.put_if_newer(base) is False
    cached = await cache.get("conversation-1")

    assert cached is not None
    assert cached.context_version == 2


@pytest.mark.asyncio
async def test_committed_message_is_published_to_l2_after_database_write() -> None:
    repository = InMemoryRunRepository()
    cache = InMemoryContextCache()
    contexts = ContextManager(repository=repository, cache=cache)
    service = RunService(
        repository=repository,
        new_id=SequenceIds(),
        context_manager=contexts,
    )

    outcome = await service.enqueue(
        CreateRunCommand(
            client_request_id="request-cache",
            conversation_id="conversation-cache",
            user_id=3,
            knowledge_base_id=5,
            question="提交后再更新缓存",
        )
    )
    cached = await cache.get("conversation-cache")

    assert outcome.context.context_version == 1
    assert cached is not None
    assert cached.context_version == 1
    assert cached.messages[0].content == "提交后再更新缓存"


@pytest.mark.asyncio
async def test_cache_below_run_minimum_version_is_rebuilt_from_database() -> None:
    repository = InMemoryRunRepository()
    writer = RunService(repository, SequenceIds())
    outcome = await writer.enqueue(
        CreateRunCommand(
            client_request_id="request-version",
            conversation_id="conversation-version",
            user_id=3,
            knowledge_base_id=5,
            question="数据库新消息",
        )
    )
    cache = InMemoryContextCache()
    stale = outcome.context.model_copy(update={"context_version": 0, "messages": []})
    await cache.put_if_newer(stale)
    contexts = ContextManager(repository, cache)

    loaded = await contexts.load(
        "conversation-version",
        user_id=3,
        minimum_version=outcome.run.context_version,
    )

    assert loaded.context_version == 1
    assert loaded.messages[0].content == "数据库新消息"


@pytest.mark.asyncio
async def test_concurrent_cache_misses_use_one_local_database_rebuild() -> None:
    repository = InMemoryRunRepository()
    await RunService(repository, SequenceIds()).enqueue(
        CreateRunCommand(
            client_request_id="request-singleflight",
            conversation_id="conversation-flight",
            user_id=3,
            knowledge_base_id=5,
            question="热点会话",
        )
    )
    delayed = DelayedContextRepository(repository)
    contexts = ContextManager(delayed, InMemoryContextCache())

    loaded = await asyncio.gather(
        *(contexts.load("conversation-flight", user_id=3) for _ in range(20))
    )

    assert delayed.load_count == 1
    assert {context.context_version for context in loaded} == {1}


@pytest.mark.asyncio
async def test_shared_coordinator_prevents_cross_instance_rebuild_stampede() -> None:
    repository = InMemoryRunRepository()
    await RunService(repository, SequenceIds()).enqueue(
        CreateRunCommand(
            client_request_id="request-distributed",
            conversation_id="conversation-shared",
            user_id=3,
            knowledge_base_id=5,
            question="跨实例热点会话",
        )
    )
    delayed = DelayedContextRepository(repository)
    cache = InMemoryContextCache()
    coordinator = InMemoryContextRebuildCoordinator()
    first = ContextManager(
        delayed,
        cache,
        rebuild_coordinator=coordinator,
        rebuild_retry_seconds=0.005,
    )
    second = ContextManager(
        delayed,
        cache,
        rebuild_coordinator=coordinator,
        rebuild_retry_seconds=0.005,
    )

    loaded = await asyncio.gather(
        first.load("conversation-shared", user_id=3),
        second.load("conversation-shared", user_id=3),
    )

    assert delayed.load_count == 1
    assert [context.context_version for context in loaded] == [1, 1]


@pytest.mark.asyncio
async def test_database_rebuilds_are_limited_across_hot_conversations() -> None:
    repository = InMemoryRunRepository()
    writer = RunService(repository, SequenceIds())
    conversation_ids = [f"hot-{index}" for index in range(5)]
    for index, conversation_id in enumerate(conversation_ids):
        await writer.enqueue(
            CreateRunCommand(
                client_request_id=f"hot-request-{index}",
                conversation_id=conversation_id,
                user_id=3,
                knowledge_base_id=5,
                question="热点限流",
            )
        )
    delayed = DelayedContextRepository(repository)
    contexts = ContextManager(
        delayed,
        InMemoryContextCache(),
        max_concurrent_rebuilds=2,
    )

    await asyncio.gather(
        *(contexts.load(conversation_id, user_id=3) for conversation_id in conversation_ids)
    )

    assert delayed.load_count == 5
    assert delayed.max_active_loads == 2
