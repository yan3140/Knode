import os
from typing import NoReturn
from uuid import uuid4

import pytest
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

from yan_agent.application.adaptive_retrieval_router import (
    AdaptiveRetrievalRouter,
    AdaptiveRoutingPolicy,
)
from yan_agent.application.qa_agent import KnowledgeQaAgent
from yan_agent.domain.qa import (
    AgentInvocation,
    AgentStatus,
    EvidenceAssessment,
    GeneratedAnswer,
    IntentDecision,
    IntentType,
    KnowledgeCitation,
    KnowledgeRetrievalRequest,
    KnowledgeRetrievalResult,
)
from yan_agent.domain.retrieval_planning import RetrievalPlanningResult, RetrievalStrategy

TEST_DSN = os.getenv("YAN_AGENT_TEST_POSTGRES_DSN")
pytestmark = pytest.mark.skipif(TEST_DSN is None, reason="PostgreSQL integration DSN is not set")


@pytest.mark.asyncio
async def test_async_postgres_checkpointer_setup_is_idempotent() -> None:
    assert TEST_DSN is not None
    async with AsyncPostgresSaver.from_conn_string(TEST_DSN) as checkpointer:
        await checkpointer.setup()
        await checkpointer.setup()
        checkpoint = await checkpointer.aget_tuple(
            {"configurable": {"thread_id": "integration-empty-thread"}}
        )

    assert checkpoint is None


class _CheckpointModel:
    def __init__(self) -> None:
        self.answer_calls = 0

    async def classify(self, question: str) -> IntentDecision:
        return IntentDecision(intent=IntentType.KNOWLEDGE)

    async def rewrite(self, question: str) -> NoReturn:
        raise AssertionError("dynamic A must not rewrite")

    async def grade(
        self,
        question: str,
        citations: list[KnowledgeCitation],
    ) -> EvidenceAssessment:
        return EvidenceAssessment(sufficient=True, reason="evidence is sufficient")

    async def answer(
        self,
        question: str,
        citations: list[KnowledgeCitation],
    ) -> GeneratedAnswer:
        self.answer_calls += 1
        return GeneratedAnswer(answer="聚簇索引按主键组织数据。[1]", citation_indices=[1])

    async def refine(
        self,
        question: str,
        previous_query: str,
        citations: list[KnowledgeCitation],
    ) -> NoReturn:
        raise AssertionError("sufficient evidence must not refine")

    async def converse(self, question: str) -> NoReturn:
        raise AssertionError("knowledge intent must not converse")


class _CheckpointRetriever:
    def __init__(self) -> None:
        self.calls = 0

    async def retrieve(
        self,
        request: KnowledgeRetrievalRequest,
    ) -> KnowledgeRetrievalResult:
        self.calls += 1
        return KnowledgeRetrievalResult(
            query_id=77,
            selected_mode=request.requested_mode,
            citations=[
                KnowledgeCitation(
                    index=1,
                    chunk_id="chunk-checkpoint",
                    document_id="document-checkpoint",
                    document_version_id="version-checkpoint",
                    title="MySQL 索引",
                    section="聚簇索引",
                    source="integration",
                    content="InnoDB 聚簇索引按主键顺序组织数据行。",
                    final_score=0.95,
                )
            ],
            degraded=False,
            degradation_reasons=[],
        )


class _UnusedAdaptivePlanner:
    strategy = RetrievalStrategy.SINGLE_REWRITE
    model_calls_per_plan = 2

    async def plan(self, request: object) -> RetrievalPlanningResult:
        raise AssertionError("dynamic A must not call Analyzer/Planner")


@pytest.mark.asyncio
async def test_dynamic_graph_recovers_completed_a_without_duplicate_generation() -> None:
    assert TEST_DSN is not None
    run_id = f"integration-dynamic-{uuid4()}"
    invocation = AgentInvocation(
        run_id=run_id,
        user_id=3,
        knowledge_base_id=5,
        question="什么是聚簇索引？",
        context_version=1,
    )
    model = _CheckpointModel()
    retriever = _CheckpointRetriever()
    router = AdaptiveRetrievalRouter(AdaptiveRoutingPolicy(shadow_mode=False))
    async with AsyncPostgresSaver.from_conn_string(TEST_DSN) as checkpointer:
        await checkpointer.setup()
        first = KnowledgeQaAgent(
            model=model,
            retriever=retriever,
            checkpointer=checkpointer,
            enable_interrupts=True,
            retrieval_router=router,
            adaptive_retrieval_planner=_UnusedAdaptivePlanner(),
        )
        completed = await first.invoke(invocation)
        restarted = KnowledgeQaAgent(
            model=model,
            retriever=retriever,
            checkpointer=checkpointer,
            enable_interrupts=True,
            retrieval_router=router,
            adaptive_retrieval_planner=_UnusedAdaptivePlanner(),
        )
        recovered = await restarted.recover(invocation)

    assert completed.status is AgentStatus.SUCCEEDED
    assert recovered == completed
    assert model.answer_calls == 1
    assert retriever.calls == 1
