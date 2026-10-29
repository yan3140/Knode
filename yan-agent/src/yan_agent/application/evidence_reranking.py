from yan_agent.domain.retrieval_fusion import FusedCandidate, RerankedCitation


class FusionScoreReranker:
    def __init__(
        self,
        *,
        max_results: int = 8,
        max_total_chars: int = 24000,
        max_chunks_per_document: int = 2,
    ) -> None:
        if max_results <= 0 or max_total_chars <= 0 or max_chunks_per_document <= 0:
            raise ValueError("reranker budgets must be positive")
        self._max_results = max_results
        self._max_total_chars = max_total_chars
        self._max_chunks_per_document = max_chunks_per_document

    async def rerank(
        self,
        question: str,
        candidates: list[FusedCandidate],
    ) -> list[RerankedCitation]:
        del question
        selected: list[RerankedCitation] = []
        used_chars = 0
        document_counts: dict[str, int] = {}
        for candidate in candidates:
            content_chars = len(candidate.citation.content)
            if used_chars + content_chars > self._max_total_chars:
                continue
            document_id = candidate.citation.document_id
            if document_counts.get(document_id, 0) >= self._max_chunks_per_document:
                continue
            selected.append(
                RerankedCitation(
                    citation=candidate.citation,
                    rank=len(selected) + 1,
                    relevance_score=candidate.cross_query_score,
                    sources=candidate.sources,
                )
            )
            used_chars += content_chars
            document_counts[document_id] = document_counts.get(document_id, 0) + 1
            if len(selected) >= self._max_results:
                break
        return selected
