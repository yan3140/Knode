import re

from yan_agent.domain.errors import AgentBudgetExceeded
from yan_agent.domain.grounding import GroundedAnswerOutcome
from yan_agent.domain.qa import GeneratedAnswer, KnowledgeCitation
from yan_agent.ports.grounding import AnswerGroundingModel

_CITATION_MARKER = re.compile(r"\[(\d+)]")
_SENTENCE = re.compile(r"[^。！？!?\n]+(?:[。！？!?](?:\s*\[\d+])*)?")


class GroundedAnswerGuard:
    def __init__(
        self,
        model: AnswerGroundingModel | None,
        max_answer_chars: int,
    ) -> None:
        self._model = model
        self._max_answer_chars = max_answer_chars

    async def finalize(
        self,
        question: str,
        generated: GeneratedAnswer,
        available: list[KnowledgeCitation],
        review_available: list[KnowledgeCitation] | None = None,
        max_model_calls: int | None = None,
    ) -> GroundedAnswerOutcome:
        answer, citations = _apply_citation_whitelist(generated, available)
        review_citations = _matching_citations(review_available or available, citations)
        issues = self._structural_issues(answer, citations)
        model_calls = 0
        if not issues and self._model is not None:
            _require_model_budget(model_calls, max_model_calls)
            review = await self._model.review_answer(question, answer, review_citations)
            model_calls += 1
            issues = [
                f"UNSUPPORTED_SENTENCE:{index}" for index in review.unsupported_sentence_indices
            ]
            if not review.valid and not issues:
                issues = ["SEMANTIC_REVIEW_FAILED"]
        if not issues:
            return GroundedAnswerOutcome(
                valid=True,
                repaired=False,
                answer=answer,
                citations=citations,
                issues=[],
                model_calls=model_calls,
            )
        if self._model is None:
            return self._failed(answer, issues, model_calls)

        _require_model_budget(model_calls, max_model_calls)
        repaired = await self._model.repair_answer(
            question,
            answer,
            review_available or available,
            issues,
        )
        model_calls += 1
        repaired_answer, repaired_citations = _apply_citation_whitelist(repaired, available)
        repaired_issues = self._structural_issues(repaired_answer, repaired_citations)
        if not repaired_issues:
            _require_model_budget(model_calls, max_model_calls)
            review = await self._model.review_answer(
                question,
                repaired_answer,
                _matching_citations(
                    review_available or available,
                    repaired_citations,
                ),
            )
            model_calls += 1
            repaired_issues = [
                f"UNSUPPORTED_SENTENCE:{index}" for index in review.unsupported_sentence_indices
            ]
            if not review.valid and not repaired_issues:
                repaired_issues = ["SEMANTIC_REVIEW_FAILED"]
        if repaired_issues:
            return self._failed(repaired_answer, repaired_issues, model_calls, repaired=True)
        return GroundedAnswerOutcome(
            valid=True,
            repaired=True,
            answer=repaired_answer,
            citations=repaired_citations,
            issues=[],
            model_calls=model_calls,
        )

    def _structural_issues(
        self,
        answer: str,
        citations: list[KnowledgeCitation],
    ) -> list[str]:
        issues: list[str] = []
        if len(answer) > self._max_answer_chars:
            issues.append("ANSWER_TOO_LONG")
        available = {citation.index for citation in citations}
        for index, sentence in enumerate(_SENTENCE.findall(answer), start=1):
            claim = _CITATION_MARKER.sub("", sentence).strip(" ，,。！？!?;；:\t")
            if not claim:
                continue
            markers = {int(value) for value in _CITATION_MARKER.findall(sentence)}
            if not markers:
                issues.append(f"MISSING_CITATION:{index}")
            elif not markers.issubset(available):
                issues.append(f"UNKNOWN_CITATION:{index}")
        return issues

    @staticmethod
    def _failed(
        answer: str,
        issues: list[str],
        model_calls: int,
        repaired: bool = False,
    ) -> GroundedAnswerOutcome:
        return GroundedAnswerOutcome(
            valid=False,
            repaired=repaired,
            answer=answer,
            citations=[],
            issues=issues,
            model_calls=model_calls,
        )


def _apply_citation_whitelist(
    generated: GeneratedAnswer,
    available: list[KnowledgeCitation],
) -> tuple[str, list[KnowledgeCitation]]:
    available_by_index = {item.index: item for item in available}
    markers = {int(match.group(1)) for match in _CITATION_MARKER.finditer(generated.answer)}
    allowed = {
        index
        for index in generated.citation_indices
        if index in available_by_index and index in markers
    }

    def retain_allowed_marker(match: re.Match[str]) -> str:
        return match.group(0) if int(match.group(1)) in allowed else ""

    answer = _CITATION_MARKER.sub(retain_allowed_marker, generated.answer)
    citations = [item for item in available if item.index in allowed]
    return answer, citations


def _matching_citations(
    available: list[KnowledgeCitation],
    selected: list[KnowledgeCitation],
) -> list[KnowledgeCitation]:
    selected_indices = {citation.index for citation in selected}
    return [citation for citation in available if citation.index in selected_indices]


def _require_model_budget(used: int, maximum: int | None) -> None:
    if maximum is not None and used >= maximum:
        raise AgentBudgetExceeded("model_calls")
