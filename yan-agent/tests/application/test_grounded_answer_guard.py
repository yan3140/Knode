import pytest

from yan_agent.application.grounding import GroundedAnswerGuard
from yan_agent.domain.grounding import CitationReview
from yan_agent.domain.qa import GeneratedAnswer, KnowledgeCitation


def citation() -> KnowledgeCitation:
    return KnowledgeCitation(
        index=1,
        chunk_id="chunk-1",
        document_id="document-1",
        document_version_id="version-1",
        title="线程池",
        section="拒绝策略",
        source="article:1",
        content="队列满时执行配置的拒绝策略。",
        final_score=0.9,
    )


class RepairingGroundingModel:
    def __init__(self) -> None:
        self.repair_calls = 0

    async def review_answer(
        self,
        question: str,
        answer: str,
        citations: list[KnowledgeCitation],
    ) -> CitationReview:
        return CitationReview(valid=True, unsupported_sentence_indices=[], reason="全部支持")

    async def repair_answer(
        self,
        question: str,
        answer: str,
        citations: list[KnowledgeCitation],
        issues: list[str],
    ) -> GeneratedAnswer:
        self.repair_calls += 1
        return GeneratedAnswer(answer="队列满时会执行拒绝策略。[1]", citation_indices=[1])


@pytest.mark.asyncio
async def test_repairs_an_uncited_sentence_once_before_returning_answer() -> None:
    model = RepairingGroundingModel()
    guard = GroundedAnswerGuard(model=model, max_answer_chars=2000)

    outcome = await guard.finalize(
        question="线程池队列满了怎么办？",
        generated=GeneratedAnswer(
            answer="队列满时会执行拒绝策略。[1] 还会自动无限重试。",
            citation_indices=[1],
        ),
        available=[citation()],
    )

    assert outcome.valid is True
    assert outcome.repaired is True
    assert outcome.answer == "队列满时会执行拒绝策略。[1]"
    assert [item.index for item in outcome.citations] == [1]
    assert model.repair_calls == 1


class BrokenRepairModel(RepairingGroundingModel):
    async def repair_answer(
        self,
        question: str,
        answer: str,
        citations: list[KnowledgeCitation],
        issues: list[str],
    ) -> GeneratedAnswer:
        self.repair_calls += 1
        return GeneratedAnswer(answer="修复后仍然没有引用。", citation_indices=[])


@pytest.mark.asyncio
async def test_rejects_answer_when_the_single_repair_is_still_ungrounded() -> None:
    model = BrokenRepairModel()
    guard = GroundedAnswerGuard(model=model, max_answer_chars=2000)

    outcome = await guard.finalize(
        question="线程池队列满了怎么办？",
        generated=GeneratedAnswer(answer="没有引用的回答。", citation_indices=[]),
        available=[citation()],
    )

    assert outcome.valid is False
    assert outcome.citations == []
    assert outcome.issues == ["MISSING_CITATION:1"]
    assert model.repair_calls == 1
