import pytest
from pydantic import ValidationError

from yan_agent.evaluation.evidence_judgments import (
    EvidenceJudgment,
    EvidenceSupport,
    JudgmentStatus,
    accepted_chunk_ids,
)


def test_pending_judgment_cannot_silently_expand_ground_truth() -> None:
    judgment = EvidenceJudgment(
        case_id="mysql-simple-001",
        chunk_id="17:18:39",
        document_id="17",
        title="MySQL 面试题",
        section="什么是覆盖索引？",
        source_experiment_ids=["experiment-1"],
    )

    assert accepted_chunk_ids([judgment]) == {}


def test_accepted_judgment_requires_auditable_rationale() -> None:
    with pytest.raises(ValidationError, match="support and rationale"):
        EvidenceJudgment(
            case_id="mysql-simple-001",
            chunk_id="17:18:39",
            document_id="17",
            title="MySQL 面试题",
            section="什么是覆盖索引？",
            status=JudgmentStatus.ACCEPTED,
            support=EvidenceSupport.FULL,
            source_experiment_ids=["experiment-1"],
        )


def test_only_accepted_full_or_partial_evidence_enters_equivalence_set() -> None:
    accepted = EvidenceJudgment(
        case_id="mysql-simple-007",
        chunk_id="17:18:39",
        document_id="17",
        title="MySQL 面试题",
        section="什么是覆盖索引？",
        status=JudgmentStatus.ACCEPTED,
        support=EvidenceSupport.FULL,
        rationale="内容完整定义覆盖索引，与当前金标 chunk 语义等价。",
        source_experiment_ids=["experiment-1", "experiment-2"],
    )

    assert accepted_chunk_ids([accepted]) == {"mysql-simple-007": {"17:18:39"}}
