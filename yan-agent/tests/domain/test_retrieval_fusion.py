from yan_agent.domain.qa import KnowledgeCitation, RetrievalMode
from yan_agent.domain.retrieval_fusion import (
    QueryRetrieval,
    fuse_query_retrievals,
)
from yan_agent.domain.retrieval_planning import QueryPurpose


def citation(chunk_id: str, document_id: str, score: float) -> KnowledgeCitation:
    return KnowledgeCitation(
        index=1,
        chunk_id=chunk_id,
        document_id=document_id,
        document_version_id=f"version-{document_id}",
        title=f"document {document_id}",
        section="section",
        source=f"article:{document_id}",
        content=f"content {chunk_id}",
        final_score=score,
    )


def retrieval(
    query_id: str,
    query_order: int,
    weight: float,
    citations: list[KnowledgeCitation],
    target_sub_question_ids: list[str] | None = None,
) -> QueryRetrieval:
    return QueryRetrieval(
        planned_query_id=query_id,
        query_order=query_order,
        query_text=query_id,
        purpose=QueryPurpose.SEMANTIC,
        weight=weight,
        java_query_id=query_order + 10,
        selected_mode=RetrievalMode.HYBRID,
        citations=citations,
        degraded=False,
        degradation_reasons=[],
        elapsed_millis=10,
        target_sub_question_ids=target_sub_question_ids or [],
    )


def test_weighted_rrf_deduplicates_and_rewards_cross_query_evidence() -> None:
    first = retrieval(
        "q-original",
        0,
        0.6,
        [citation("chunk-a", "doc-a", 0.9), citation("chunk-shared", "doc-s", 0.8)],
    )
    second = retrieval(
        "q-semantic",
        1,
        1.0,
        [citation("chunk-shared", "doc-s", 0.7), citation("chunk-b", "doc-b", 0.95)],
    )

    fused = fuse_query_retrievals([second, first], rank_constant=60, limit=20)

    assert [item.citation.chunk_id for item in fused] == [
        "chunk-shared",
        "chunk-b",
        "chunk-a",
    ]
    shared = fused[0]
    assert [source.planned_query_id for source in shared.sources] == [
        "q-original",
        "q-semantic",
    ]
    assert shared.cross_query_score == (0.6 / 62) + (1.0 / 61)


def test_fusion_is_order_independent_and_limits_chunks_per_document() -> None:
    first = retrieval(
        "q-1",
        0,
        1.0,
        [
            citation("chunk-a1", "doc-a", 0.9),
            citation("chunk-a2", "doc-a", 0.8),
            citation("chunk-a3", "doc-a", 0.7),
        ],
    )
    second = retrieval(
        "q-2",
        1,
        1.0,
        [citation("chunk-b1", "doc-b", 0.9)],
    )

    forward = fuse_query_retrievals(
        [first, second],
        rank_constant=60,
        limit=20,
        max_chunks_per_document=2,
    )
    reversed_results = fuse_query_retrievals(
        [second, first],
        rank_constant=60,
        limit=20,
        max_chunks_per_document=2,
    )

    forward_ids = [item.citation.chunk_id for item in forward]
    assert forward_ids == [item.citation.chunk_id for item in reversed_results]
    assert forward_ids == ["chunk-a1", "chunk-b1", "chunk-a2"]


def test_fusion_reserves_subquestion_coverage_before_global_rrf() -> None:
    first = retrieval(
        "q-1",
        1,
        1.0,
        [citation(f"chunk-a{i}", "doc-a", 1 - i / 10) for i in range(1, 6)],
        ["sq-1"],
    )
    second = retrieval(
        "q-2",
        2,
        1.0,
        [citation("chunk-b1", "doc-b", 0.8), citation("chunk-b2", "doc-b", 0.7)],
        ["sq-2"],
    )

    fused = fuse_query_retrievals(
        [first, second],
        rank_constant=20,
        limit=4,
        max_chunks_per_document=3,
        minimum_per_subquestion=1,
    )

    ids = [item.citation.chunk_id for item in fused]
    assert ids[:2] == ["chunk-a1", "chunk-b1"]
    assert len([item for item in fused if item.citation.document_id == "doc-a"]) <= 3
