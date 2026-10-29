from typing import Protocol

from yan_agent.domain.grounding import CitationReview
from yan_agent.domain.qa import GeneratedAnswer, KnowledgeCitation


class AnswerGroundingModel(Protocol):
    async def review_answer(
        self,
        question: str,
        answer: str,
        citations: list[KnowledgeCitation],
    ) -> CitationReview: ...

    async def repair_answer(
        self,
        question: str,
        answer: str,
        citations: list[KnowledgeCitation],
        issues: list[str],
    ) -> GeneratedAnswer: ...
