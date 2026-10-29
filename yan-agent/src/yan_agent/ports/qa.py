from typing import Protocol

from yan_agent.domain.qa import (
    EvidenceAssessment,
    GeneratedAnswer,
    IntentDecision,
    KnowledgeCitation,
    KnowledgeRetrievalRequest,
    KnowledgeRetrievalResult,
    QueryRewrite,
)


class AgentModel(Protocol):
    async def classify(self, question: str) -> IntentDecision: ...

    async def rewrite(self, question: str) -> QueryRewrite: ...

    async def grade(
        self, question: str, citations: list[KnowledgeCitation]
    ) -> EvidenceAssessment: ...

    async def refine(
        self,
        question: str,
        previous_query: str,
        citations: list[KnowledgeCitation],
    ) -> QueryRewrite: ...

    async def answer(
        self, question: str, citations: list[KnowledgeCitation]
    ) -> GeneratedAnswer: ...

    async def converse(self, question: str) -> str: ...


class KnowledgeRetriever(Protocol):
    async def retrieve(self, request: KnowledgeRetrievalRequest) -> KnowledgeRetrievalResult: ...
