from enum import StrEnum

from pydantic import Field, model_validator

from yan_agent.evaluation.live_retrieval_runner import (
    LiveCaseRetrievalResult,
    LiveReportModel,
    LiveRetrievalReport,
)


class EvidenceSupport(StrEnum):
    FULL = "FULL"
    PARTIAL = "PARTIAL"
    NONE = "NONE"


class JudgmentStatus(StrEnum):
    PENDING = "PENDING"
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"


class EvidenceJudgment(LiveReportModel):
    case_id: str
    chunk_id: str
    document_id: str
    title: str
    section: str
    support: EvidenceSupport | None = None
    status: JudgmentStatus = JudgmentStatus.PENDING
    rationale: str | None = Field(default=None, max_length=2000)
    source_experiment_ids: list[str] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_review(self) -> "EvidenceJudgment":
        if self.status is JudgmentStatus.PENDING:
            if self.support is not None or self.rationale is not None:
                raise ValueError("pending judgments must not contain a decision")
        elif self.support is None or not self.rationale or not self.rationale.strip():
            raise ValueError("reviewed judgments require support and rationale")
        if self.status is JudgmentStatus.ACCEPTED and self.support is EvidenceSupport.NONE:
            raise ValueError("accepted evidence must provide full or partial support")
        if self.status is JudgmentStatus.REJECTED and self.support is not EvidenceSupport.NONE:
            raise ValueError("rejected evidence must use NONE support")
        return self


def build_pending_evidence_judgments(
    reports: list[LiveRetrievalReport],
    *,
    candidate_limit_per_case: int = 5,
) -> list[EvidenceJudgment]:
    if not reports:
        raise ValueError("at least one retrieval report is required")
    if candidate_limit_per_case < 1:
        raise ValueError("candidate_limit_per_case must be positive")
    _validate_compatible_reports(reports)
    experiments_by_candidate: dict[tuple[str, str], set[str]] = {}
    candidate_by_key: dict[tuple[str, str], tuple[LiveCaseRetrievalResult, int]] = {}
    for report in reports:
        for case in report.cases:
            expected = set(case.expected_chunk_ids)
            for citation in case.final_ranking[:candidate_limit_per_case]:
                if citation.chunk_id in expected:
                    continue
                key = (case.case_id, citation.chunk_id)
                experiments_by_candidate.setdefault(key, set()).add(report.experiment_id)
                candidate_by_key.setdefault(key, (case, citation.rank))
    judgments = []
    for key in sorted(
        candidate_by_key,
        key=lambda item: (item[0], candidate_by_key[item][1], item[1]),
    ):
        case, _rank = candidate_by_key[key]
        citation = next(item for item in case.final_ranking if item.chunk_id == key[1])
        judgments.append(
            EvidenceJudgment(
                case_id=case.case_id,
                chunk_id=citation.chunk_id,
                document_id=citation.document_id,
                title=citation.title,
                section=citation.section,
                source_experiment_ids=sorted(experiments_by_candidate[key]),
            )
        )
    return judgments


def accepted_chunk_ids(
    judgments: list[EvidenceJudgment],
) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for judgment in judgments:
        if judgment.status is JudgmentStatus.ACCEPTED:
            result.setdefault(judgment.case_id, set()).add(judgment.chunk_id)
    return result


def _validate_compatible_reports(reports: list[LiveRetrievalReport]) -> None:
    reference = reports[0]
    reference_cases = [case.case_id for case in reference.cases]
    for report in reports[1:]:
        if (
            report.dataset_id != reference.dataset_id
            or report.dataset_sha256 != reference.dataset_sha256
            or [case.case_id for case in report.cases] != reference_cases
        ):
            raise ValueError("evidence candidate reports must use the same frozen dataset")
