import pytest

from yan_agent.application.qa_agent import KnowledgeQaAgent
from yan_agent.domain.budget import AgentBudgetPolicy
from yan_agent.domain.errors import AgentBudgetExceeded, AgentErrorCode
from yan_agent.domain.qa import (
    AgentInvocation,
    EvidenceAssessment,
    GeneratedAnswer,
    IntentDecision,
    IntentType,
    KnowledgeCitation,
    KnowledgeRetrievalRequest,
    KnowledgeRetrievalResult,
    QueryRewrite,
    RetrievalMode,
)


class CountingModel:
    def __init__(self) -> None:
        self.calls: list[str] = []

    async def classify(self, question: str) -> IntentDecision:
        self.calls.append("classify")
        return IntentDecision(intent=IntentType.KNOWLEDGE)

    async def rewrite(self, question: str) -> QueryRewrite:
        self.calls.append("rewrite")
        return QueryRewrite(query=question, mode=RetrievalMode.HYBRID)

    async def grade(self, question: str, citations: list[KnowledgeCitation]) -> EvidenceAssessment:
        self.calls.append("grade")
        return EvidenceAssessment(sufficient=True, reason="enough")

    async def answer(self, question: str, citations: list[KnowledgeCitation]) -> GeneratedAnswer:
        self.calls.append("answer")
        return GeneratedAnswer(answer="有依据。[1]", citation_indices=[1])

    async def refine(
        self,
        question: str,
        previous_query: str,
        citations: list[KnowledgeCitation],
    ) -> QueryRewrite:
        self.calls.append("refine")
        return QueryRewrite(query=previous_query, mode=RetrievalMode.HYBRID)

    async def converse(self, question: str) -> str:
        self.calls.append("converse")
        return "hello"


class StaticRetriever:
    async def retrieve(self, request: KnowledgeRetrievalRequest) -> KnowledgeRetrievalResult:
        return KnowledgeRetrievalResult(
            query_id=1,
            selected_mode=RetrievalMode.HYBRID,
            citations=[
                KnowledgeCitation(
                    index=1,
                    chunk_id="chunk-1",
                    document_id="document-1",
                    document_version_id="version-1",
                    title="title",
                    section="section",
                    source="article:1",
                    content="有依据。",
                )
            ],
            degraded=False,
            degradation_reasons=[],
        )


def invocation(run_id: str) -> AgentInvocation:
    return AgentInvocation(
        run_id=run_id,
        user_id=1,
        knowledge_base_id=1,
        question="问题",
    )


@pytest.mark.asyncio
async def test_model_call_budget_stops_before_excess_call() -> None:
    model = CountingModel()
    agent = KnowledgeQaAgent(
        model=model,
        retriever=StaticRetriever(),
        budget=AgentBudgetPolicy(max_model_calls=3, max_total_seconds=30),
    )

    with pytest.raises(AgentBudgetExceeded) as raised:
        await agent.invoke(invocation("run-call-budget"))

    assert raised.value.code is AgentErrorCode.BUDGET_EXCEEDED
    assert model.calls == ["classify", "rewrite", "grade"]


@pytest.mark.asyncio
async def test_total_deadline_stops_before_first_model_call() -> None:
    model = CountingModel()
    values = iter((100.0, 102.0))

    def clock() -> float:
        return next(values)

    agent = KnowledgeQaAgent(
        model=model,
        retriever=StaticRetriever(),
        budget=AgentBudgetPolicy(max_model_calls=10, max_total_seconds=1),
        clock=clock,
    )

    with pytest.raises(AgentBudgetExceeded):
        await agent.invoke(invocation("run-time-budget"))

    assert model.calls == []


class ContextRecordingModel(CountingModel):
    def __init__(self) -> None:
        super().__init__()
        self.conversation_input = ""

    async def classify(self, question: str) -> IntentDecision:
        self.calls.append("classify")
        return IntentDecision(intent=IntentType.CONVERSATIONAL)

    async def converse(self, question: str) -> str:
        self.calls.append("converse")
        self.conversation_input = question
        return "hello"


@pytest.mark.asyncio
async def test_conversation_context_has_a_hard_character_budget() -> None:
    model = ContextRecordingModel()
    agent = KnowledgeQaAgent(
        model=model,
        retriever=StaticRetriever(),
        budget=AgentBudgetPolicy(
            max_model_calls=10,
            max_total_seconds=30,
            max_context_chars=100,
        ),
    )
    request = invocation("run-context-budget").model_copy(
        update={"conversation_context": "old-context-" * 30 + "recent-context"}
    )

    result = await agent.invoke(request)

    assert len(model.conversation_input) < 180
    assert "recent-context" in model.conversation_input
    assert result.security_flags == ["CONTEXT_TRUNCATED"]
