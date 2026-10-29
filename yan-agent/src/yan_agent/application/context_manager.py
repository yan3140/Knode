import asyncio
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

from yan_agent.domain.runs import ConversationAccessDenied, ConversationContext
from yan_agent.observability.metrics import AgentMetrics
from yan_agent.ports.context_cache import ContextCache, ContextCacheUnavailable
from yan_agent.ports.context_rebuild import (
    ContextRebuildCoordinator,
    ContextRebuildCoordinatorUnavailable,
)
from yan_agent.ports.runs import RunRepository


@dataclass
class _LockEntry:
    lock: asyncio.Lock
    users: int = 0


class _KeyedLocks:
    def __init__(self) -> None:
        self._guard = asyncio.Lock()
        self._entries: dict[str, _LockEntry] = {}

    @asynccontextmanager
    async def hold(self, key: str) -> AsyncIterator[None]:
        async with self._guard:
            entry = self._entries.setdefault(key, _LockEntry(asyncio.Lock()))
            entry.users += 1
        await entry.lock.acquire()
        try:
            yield
        finally:
            entry.lock.release()
            async with self._guard:
                entry.users -= 1
                if entry.users == 0:
                    self._entries.pop(key, None)


class ContextManager:
    def __init__(
        self,
        repository: RunRepository,
        cache: ContextCache,
        rebuild_coordinator: ContextRebuildCoordinator | None = None,
        rebuild_lease_seconds: int = 5,
        rebuild_retry_seconds: float = 0.05,
        max_concurrent_rebuilds: int = 16,
        metrics: AgentMetrics | None = None,
    ) -> None:
        if rebuild_lease_seconds < 1:
            raise ValueError("rebuild_lease_seconds must be positive")
        if rebuild_retry_seconds <= 0:
            raise ValueError("rebuild_retry_seconds must be positive")
        if max_concurrent_rebuilds < 1:
            raise ValueError("max_concurrent_rebuilds must be positive")
        self._repository = repository
        self._cache = cache
        self._rebuild_coordinator = rebuild_coordinator
        self._rebuild_lease_seconds = rebuild_lease_seconds
        self._rebuild_retry_seconds = rebuild_retry_seconds
        self._rebuild_slots = asyncio.Semaphore(max_concurrent_rebuilds)
        self._local_rebuilds = _KeyedLocks()
        self._metrics = metrics

    async def load(
        self,
        conversation_id: str,
        user_id: int,
        minimum_version: int = 0,
    ) -> ConversationContext:
        cached = await self._load_cached(conversation_id)
        self._record_stale(cached, minimum_version)
        if self._is_usable(cached, user_id, minimum_version):
            assert cached is not None
            return cached

        async with self._local_rebuilds.hold(conversation_id):
            cached = await self._load_cached(conversation_id)
            self._record_stale(cached, minimum_version)
            if self._is_usable(cached, user_id, minimum_version):
                assert cached is not None
                return cached
            return await self._coordinated_rebuild(
                conversation_id,
                user_id,
                minimum_version,
            )

    async def publish_committed(self, context: ConversationContext) -> None:
        try:
            written = await self._cache.put_if_newer(context)
        except ContextCacheUnavailable:
            self._observe_context("l2", "unavailable")
            return
        self._observe_context("l2", "written" if written else "newer_present")

    async def invalidate(self, conversation_id: str) -> None:
        try:
            await self._cache.delete(conversation_id)
        except ContextCacheUnavailable:
            return

    async def _load_cached(self, conversation_id: str) -> ConversationContext | None:
        try:
            context = await self._cache.get(conversation_id)
        except ContextCacheUnavailable:
            self._observe_context("l2", "unavailable")
            return None
        self._observe_context("l2", "hit" if context is not None else "miss")
        return context

    async def _coordinated_rebuild(
        self,
        conversation_id: str,
        user_id: int,
        minimum_version: int,
    ) -> ConversationContext:
        coordinator = self._rebuild_coordinator
        if coordinator is None:
            return await self._load_from_source(conversation_id, user_id)

        available, token = await self._try_acquire(conversation_id)
        if not available:
            self._observe_context("coordination", "unavailable")
            return await self._load_from_source(conversation_id, user_id)
        if token is not None:
            self._observe_context("coordination", "acquired")
            return await self._load_with_lease(conversation_id, user_id, token)
        self._observe_context("coordination", "waiting")

        while True:
            await asyncio.sleep(self._rebuild_retry_seconds)
            cached = await self._load_cached(conversation_id)
            self._record_stale(cached, minimum_version)
            if self._is_usable(cached, user_id, minimum_version):
                assert cached is not None
                return cached
            available, token = await self._try_acquire(conversation_id)
            if not available:
                self._observe_context("coordination", "unavailable")
                return await self._load_from_source(conversation_id, user_id)
            if token is not None:
                self._observe_context("coordination", "acquired")
                return await self._load_with_lease(conversation_id, user_id, token)

    async def _load_with_lease(
        self,
        conversation_id: str,
        user_id: int,
        token: str,
    ) -> ConversationContext:
        try:
            return await self._load_from_source(conversation_id, user_id)
        finally:
            coordinator = self._rebuild_coordinator
            assert coordinator is not None
            try:
                await coordinator.release(conversation_id, token)
            except ContextRebuildCoordinatorUnavailable:
                pass

    async def _load_from_source(
        self,
        conversation_id: str,
        user_id: int,
    ) -> ConversationContext:
        started_at = time.perf_counter()
        async with self._rebuild_slots:
            try:
                context = await self._repository.load_context(conversation_id, user_id)
            except Exception:
                self._observe_context("l3", "error")
                raise
        self._observe_context("l3", "loaded")
        if self._metrics is not None:
            self._metrics.observe_context_rebuild(time.perf_counter() - started_at)
        await self.publish_committed(context)
        return context

    def _record_stale(
        self,
        context: ConversationContext | None,
        minimum_version: int,
    ) -> None:
        if context is None or context.context_version >= minimum_version:
            return
        self._observe_context("l2", "stale")
        if self._metrics is not None:
            self._metrics.observe_context_version_conflict()

    def _observe_context(self, tier: str, outcome: str) -> None:
        if self._metrics is not None:
            self._metrics.observe_context(tier, outcome)

    async def _try_acquire(self, conversation_id: str) -> tuple[bool, str | None]:
        coordinator = self._rebuild_coordinator
        assert coordinator is not None
        try:
            token = await coordinator.acquire(
                conversation_id,
                self._rebuild_lease_seconds,
            )
        except ContextRebuildCoordinatorUnavailable:
            return False, None
        return True, token

    @staticmethod
    def _is_usable(
        context: ConversationContext | None,
        user_id: int,
        minimum_version: int,
    ) -> bool:
        if context is None or context.context_version < minimum_version:
            return False
        if context.user_id != user_id:
            raise ConversationAccessDenied("Conversation belongs to another user")
        return True
