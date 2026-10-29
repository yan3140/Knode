import pytest

from yan_agent.application.evidence_reranking import FusionScoreReranker
from yan_agent.domain.qa import KnowledgeCitation
from yan_agent.domain.retrieval_fusion import CandidateSource, FusedCandidate


def candidate(index: int, content_chars: int, score: float) -> FusedCandidate:
    return FusedCandidate(
        citation=KnowledgeCitation(
            index=index,
            chunk_id=f"chunk-{index}",
            document_id=f"document-{index}",
            document_version_id=f"version-{index}",
            title=f"title {index}",
            section="section",
            source=f"article:{index}",
            content="x" * content_chars,
            final_score=0.9,
        ),
        cross_query_score=score,
        sources=[
            CandidateSource(
                planned_query_id="q-1",
                query_order=0,
                rank=index,
                query_weight=1.0,
                original_score=0.9,
            )
        ],
    )


@pytest.mark.asyncio
async def test_fusion_reranker_respects_result_and_context_budgets() -> None:
    reranker = FusionScoreReranker(max_results=3, max_total_chars=10)

    ranked = await reranker.rerank(
        "question",
        [
            candidate(1, 6, 0.3),
            candidate(2, 6, 0.2),
            candidate(3, 4, 0.1),
            candidate(4, 1, 0.05),
        ],
    )

    assert [item.citation.chunk_id for item in ranked] == ["chunk-1", "chunk-3"]
    assert [item.rank for item in ranked] == [1, 2]
    assert sum(len(item.citation.content) for item in ranked) <= 10
