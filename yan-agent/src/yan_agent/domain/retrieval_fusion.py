from pydantic import Field

from yan_agent.domain.qa import DomainModel, KnowledgeCitation, RetrievalMode
from yan_agent.domain.retrieval_planning import QueryPurpose


class QueryRetrieval(DomainModel):
    planned_query_id: str = Field(min_length=1, max_length=64)
    query_order: int = Field(ge=0, le=3)
    query_text: str = Field(min_length=1, max_length=2000)
    purpose: QueryPurpose
    weight: float = Field(gt=0, le=1)
    java_query_id: int | None = Field(default=None, gt=0)
    selected_mode: RetrievalMode | None = None
    citations: list[KnowledgeCitation]
    degraded: bool
    degradation_reasons: list[str]
    elapsed_millis: float = Field(ge=0)
    error_code: str | None = None
    error_message: str | None = None
    target_sub_question_ids: list[str] = Field(default_factory=list)


class CandidateSource(DomainModel):
    planned_query_id: str
    query_order: int
    rank: int = Field(gt=0)
    query_weight: float = Field(gt=0, le=1)
    original_score: float | None = None


class FusedCandidate(DomainModel):
    citation: KnowledgeCitation
    cross_query_score: float = Field(gt=0)
    sources: list[CandidateSource]


class RerankedCitation(DomainModel):
    citation: KnowledgeCitation
    rank: int = Field(gt=0)
    relevance_score: float = Field(ge=0)
    sources: list[CandidateSource]


def fuse_query_retrievals(
    retrievals: list[QueryRetrieval],
    *,
    rank_constant: int = 60,
    limit: int = 20,
    max_chunks_per_document: int | None = None,
    minimum_per_subquestion: int = 0,
) -> list[FusedCandidate]:
    if rank_constant < 0:
        raise ValueError("rank_constant must not be negative")
    if limit <= 0:
        raise ValueError("limit must be positive")
    if max_chunks_per_document is not None and max_chunks_per_document <= 0:
        raise ValueError("max_chunks_per_document must be positive")
    if minimum_per_subquestion < 0:
        raise ValueError("minimum_per_subquestion must not be negative")

    candidates: dict[str, tuple[KnowledgeCitation, float, list[CandidateSource]]] = {}
    for retrieval in sorted(retrievals, key=lambda item: item.query_order):
        seen_in_query: set[str] = set()
        for rank, citation in enumerate(retrieval.citations, start=1):
            if citation.chunk_id in seen_in_query:
                continue
            seen_in_query.add(citation.chunk_id)
            score_increment = retrieval.weight / (rank_constant + rank)
            source = CandidateSource(
                planned_query_id=retrieval.planned_query_id,
                query_order=retrieval.query_order,
                rank=rank,
                query_weight=retrieval.weight,
                original_score=citation.final_score,
            )
            existing = candidates.get(citation.chunk_id)
            if existing is None:
                candidates[citation.chunk_id] = (citation, score_increment, [source])
            else:
                existing_citation, score, sources = existing
                candidates[citation.chunk_id] = (
                    existing_citation,
                    score + score_increment,
                    [*sources, source],
                )

    fused = [
        FusedCandidate(
            citation=citation,
            cross_query_score=score,
            sources=sources,
        )
        for citation, score, sources in candidates.values()
    ]
    fused.sort(
        key=lambda item: (
            -item.cross_query_score,
            min(source.query_order for source in item.sources),
            min(source.rank for source in item.sources),
            item.citation.chunk_id,
        )
    )
    document_counts: dict[str, int] = {}
    selected: list[FusedCandidate] = []
    selected_ids: set[str] = set()

    query_subquestions = {
        retrieval.planned_query_id: retrieval.target_sub_question_ids for retrieval in retrievals
    }
    subquestions = sorted(
        {subquestion_id for ids in query_subquestions.values() for subquestion_id in ids}
    )
    for _round in range(minimum_per_subquestion):
        for subquestion_id in subquestions:
            candidate = next(
                (
                    item
                    for item in fused
                    if item.citation.chunk_id not in selected_ids
                    and any(
                        subquestion_id in query_subquestions.get(source.planned_query_id, [])
                        for source in item.sources
                    )
                    and _document_has_capacity(
                        item.citation.document_id,
                        document_counts,
                        max_chunks_per_document,
                    )
                ),
                None,
            )
            if candidate is None:
                continue
            selected.append(candidate)
            selected_ids.add(candidate.citation.chunk_id)
            document_id = candidate.citation.document_id
            document_counts[document_id] = document_counts.get(document_id, 0) + 1
            if len(selected) >= limit:
                return selected

    for candidate in fused:
        if candidate.citation.chunk_id in selected_ids:
            continue
        document_id = candidate.citation.document_id
        if not _document_has_capacity(
            document_id,
            document_counts,
            max_chunks_per_document,
        ):
            continue
        selected.append(candidate)
        selected_ids.add(candidate.citation.chunk_id)
        document_counts[document_id] = document_counts.get(document_id, 0) + 1
        if len(selected) >= limit:
            break
    return selected


def _document_has_capacity(
    document_id: str,
    document_counts: dict[str, int],
    maximum: int | None,
) -> bool:
    return maximum is None or document_counts.get(document_id, 0) < maximum
