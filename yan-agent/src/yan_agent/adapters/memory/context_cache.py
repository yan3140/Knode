import asyncio

from yan_agent.domain.runs import ConversationContext


class InMemoryContextCache:
    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._contexts: dict[str, ConversationContext] = {}

    async def get(self, conversation_id: str) -> ConversationContext | None:
        context = self._contexts.get(conversation_id)
        return context.model_copy(deep=True) if context is not None else None

    async def put_if_newer(self, context: ConversationContext) -> bool:
        async with self._lock:
            current = self._contexts.get(context.conversation_id)
            if current is not None and current.context_version >= context.context_version:
                return False
            self._contexts[context.conversation_id] = context.model_copy(deep=True)
            return True

    async def delete(self, conversation_id: str) -> None:
        self._contexts.pop(conversation_id, None)
