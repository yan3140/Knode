from typing import Protocol

from yan_agent.domain.runs import ConversationContext


class ContextCacheUnavailable(RuntimeError):
    pass


class ContextCache(Protocol):
    async def get(self, conversation_id: str) -> ConversationContext | None: ...

    async def put_if_newer(self, context: ConversationContext) -> bool: ...

    async def delete(self, conversation_id: str) -> None: ...
