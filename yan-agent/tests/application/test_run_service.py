import pytest

from yan_agent.adapters.memory.run_repository import InMemoryRunRepository
from yan_agent.application.run_service import RunService
from yan_agent.domain.qa import RetrievalMode
from yan_agent.domain.runs import CreateRunCommand, IdempotencyConflict


class SequenceIds:
    def __init__(self) -> None:
        self._values = iter(["run-1", "message-1", "run-2", "message-2"])

    def __call__(self) -> str:
        return next(self._values)


@pytest.mark.asyncio
async def test_duplicate_client_request_creates_one_run_and_one_question() -> None:
    repository = InMemoryRunRepository()
    service = RunService(repository=repository, new_id=SequenceIds())
    command = CreateRunCommand(
        client_request_id="request-1",
        conversation_id="conversation-1",
        user_id=3,
        knowledge_base_id=5,
        question="线程池队列满了怎么办？",
        requested_mode=RetrievalMode.AUTO,
    )

    first = await service.enqueue(command)
    duplicate = await service.enqueue(command)
    context = await service.load_context("conversation-1", user_id=3)

    assert first.run.id == duplicate.run.id == "run-1"
    assert first.created is True
    assert duplicate.created is False
    assert context.context_version == 1
    assert [message.content for message in context.messages] == ["线程池队列满了怎么办？"]


@pytest.mark.asyncio
async def test_reused_idempotency_key_rejects_different_payload() -> None:
    service = RunService(repository=InMemoryRunRepository(), new_id=SequenceIds())
    original = CreateRunCommand(
        client_request_id="request-1",
        conversation_id="conversation-1",
        user_id=3,
        knowledge_base_id=5,
        question="第一个问题",
    )
    await service.enqueue(original)

    with pytest.raises(IdempotencyConflict):
        await service.enqueue(original.model_copy(update={"question": "被替换的问题"}))
