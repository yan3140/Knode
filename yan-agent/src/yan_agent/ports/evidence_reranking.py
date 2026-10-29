from typing import Protocol

from yan_agent.domain.retrieval_fusion import FusedCandidate, RerankedCitation


class EvidenceReranker(Protocol):
    async def rerank(
        self,
        question: str,
        candidates: list[FusedCandidate],
    ) -> list[RerankedCitation]: ...
