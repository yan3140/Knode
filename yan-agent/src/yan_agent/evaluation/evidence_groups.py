"""Fact-level evidence scoring, separate from legacy strict chunk recall.

An alternative is a sufficient set of chunks (AND); alternatives within a
group are interchangeable (OR). All groups are required for full coverage.
"""

from collections.abc import Sequence

from pydantic import BaseModel, ConfigDict, Field, model_validator


class EvidenceGroup(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    fact: str = Field(min_length=1)
    alternatives: list[list[str]] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_alternatives(self) -> "EvidenceGroup":
        seen: set[frozenset[str]] = set()
        if not self.fact.strip():
            raise ValueError("fact must not be blank")
        for alternative in self.alternatives:
            if not alternative or any(not chunk.strip() for chunk in alternative):
                raise ValueError("each alternative must contain nonblank chunk IDs")
            key = frozenset(alternative)
            if len(key) != len(alternative) or key in seen:
                raise ValueError("duplicate chunk IDs or equivalent alternatives")
            seen.add(key)
        return self


def score_evidence_groups(
    groups: Sequence[EvidenceGroup],
    ranked_chunk_ids: Sequence[str],
    *,
    top_k: int = 10,
    failed: bool = False,
) -> dict[str, object]:
    """Score answerable questions; a failed request contributes zero quality."""
    if top_k < 1:
        raise ValueError("top_k must be positive")
    if not groups:
        raise ValueError("answerable cases require at least one evidence group")
    retrieved = set() if failed else set(ranked_chunk_ids[:top_k])
    coverage = [
        any(set(alternative).issubset(retrieved) for alternative in group.alternatives)
        for group in groups
    ]
    relevant = {chunk for group in groups for alt in group.alternatives for chunk in alt}
    return {
        "hitAt10": float(any(coverage)),
        "evidenceGroupRecallAt10": sum(coverage) / len(groups),
        "completeEvidenceHitAt10": float(all(coverage)),
        "strictChunkRecallAt10": len(retrieved & relevant) / len(relevant),
        "coveredGroups": coverage,
        "missingFacts": [
            group.fact for group, covered in zip(groups, coverage, strict=False) if not covered
        ],
        "failed": failed,
    }
