from typing import Protocol

from yan_agent.domain.question_analysis import QuestionAnalysisDraft


class QuestionAnalysisModel(Protocol):
    async def analyze_question(
        self,
        original_question: str,
        model_input: str,
        required_exact_terms: list[str],
    ) -> QuestionAnalysisDraft: ...
