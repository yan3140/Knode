from dataclasses import dataclass, field

import pytest

from yan_agent.application.retrieval_planning import (
    MultiQueryPlanner,
    RawQueryPlanner,
    SingleRewritePlanner,
)
from yan_agent.domain.errors import ModelInvalidResponseError
from yan_agent.domain.qa import QueryRewrite, RetrievalMode
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    RetrievalPlan,
    RetrievalPlanDraft,
    RetrievalPlanningRequest,
    RetrievalStrategy,
    SubQuestion,
)


@dataclass
class RecordingRewriteModel:
    rewrite_result: QueryRewrite
    questions: list[str] = field(default_factory=list)

    async def rewrite(self, question: str) -> QueryRewrite:
        self.questions.append(question)
        return self.rewrite_result


@dataclass
class RecordingPlanningModel:
    plan_result: RetrievalPlan | RetrievalPlanDraft
    inputs: list[tuple[str, str]] = field(default_factory=list)

    async def plan_retrieval(
        self,
        original_question: str,
        model_input: str,
        required_exact_terms: list[str],
    ) -> RetrievalPlanDraft:
        self.inputs.append((original_question, model_input))
        return self.plan_result


@pytest.mark.asyncio
async def test_raw_query_planner_uses_current_question_without_model_call() -> None:
    planner = RawQueryPlanner()

    result = await planner.plan(
        RetrievalPlanningRequest(
            original_question="它为什么会失效？",
            model_input="<conversation_context>上轮讨论联合索引</conversation_context>",
            requested_mode=RetrievalMode.KEYWORD,
            conversation_context_present=True,
        )
    )

    assert result.strategy is RetrievalStrategy.RAW_QUERY
    assert result.query.query == "它为什么会失效？"
    assert result.query.mode is RetrievalMode.KEYWORD
    assert result.model_calls == 0
    assert result.planner_version == "raw-query.v2"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("question", "expected_mode"),
    [
        ("DB_TRX_ID 保存什么？", RetrievalMode.HYBRID),
        ("redo log 最主要的用途是什么？", RetrievalMode.HYBRID),
        ("什么是覆盖索引？", RetrievalMode.HYBRID),
    ],
)
async def test_raw_query_planner_selects_deterministic_mode_for_auto(
    question: str,
    expected_mode: RetrievalMode,
) -> None:
    result = await RawQueryPlanner().plan(
        RetrievalPlanningRequest(
            original_question=question,
            model_input=question,
        )
    )

    assert result.query.mode is expected_mode


@pytest.mark.asyncio
async def test_single_rewrite_planner_uses_context_and_preserves_explicit_mode() -> None:
    model = RecordingRewriteModel(
        QueryRewrite(query="联合索引失效的原因", mode=RetrievalMode.VECTOR)
    )
    planner = SingleRewritePlanner(model)
    model_input = (
        "<conversation_context>\n上轮讨论联合索引\n"
        "</conversation_context>\n当前问题：它为什么会失效？"
    )

    result = await planner.plan(
        RetrievalPlanningRequest(
            original_question="它为什么会失效？",
            model_input=model_input,
            requested_mode=RetrievalMode.EXACT,
            conversation_context_present=True,
        )
    )

    assert model.questions == [model_input]
    assert result.strategy is RetrievalStrategy.SINGLE_REWRITE
    assert result.query.query == "联合索引失效的原因"
    assert result.query.mode is RetrievalMode.EXACT
    assert result.model_calls == 1
    assert result.planner_version == "single-rewrite.v1"


@pytest.mark.asyncio
async def test_multi_query_planner_preserves_explicit_mode_for_every_query() -> None:
    model = RecordingPlanningModel(
        RetrievalPlan(
            original_question="MVCC 和幻读有什么关系？",
            standalone_question="MySQL MVCC 和幻读有什么关系？",
            complexity=QueryComplexity.COMPOUND,
            sub_questions=[SubQuestion(sub_question_id="sq-1", text="MVCC 如何工作？")],
            queries=[
                PlannedQuery(
                    query_id="q-original",
                    text="MVCC 和幻读有什么关系？",
                    purpose=QueryPurpose.ORIGINAL,
                    mode=RetrievalMode.HYBRID,
                    weight=0.6,
                    target_sub_question_ids=[],
                ),
                PlannedQuery(
                    query_id="q-semantic",
                    text="MySQL MVCC 可重复读 幻读",
                    purpose=QueryPurpose.SEMANTIC,
                    mode=RetrievalMode.VECTOR,
                    weight=1.0,
                    target_sub_question_ids=[],
                ),
            ],
            exact_terms=[],
            filters={},
            planner_version="multi-query.v1",
        )
    )
    planner = MultiQueryPlanner(model)

    result = await planner.plan(
        RetrievalPlanningRequest(
            original_question="MVCC 和幻读有什么关系？",
            model_input="MVCC 和幻读有什么关系？",
            requested_mode=RetrievalMode.KEYWORD,
        )
    )

    assert result.strategy is RetrievalStrategy.MULTI_QUERY
    assert result.plan is not None
    assert {query.mode for query in result.plan.queries} == {RetrievalMode.KEYWORD}
    assert result.query.query == "MVCC 和幻读有什么关系？"
    assert result.model_calls == 1
    assert model.inputs == [("MVCC 和幻读有什么关系？", "MVCC 和幻读有什么关系？")]


@pytest.mark.asyncio
async def test_multi_query_planner_rejects_changed_original_question() -> None:
    model = RecordingPlanningModel(
        RetrievalPlan(
            original_question="另一个问题",
            standalone_question="另一个问题",
            complexity=QueryComplexity.SIMPLE,
            sub_questions=[],
            queries=[
                PlannedQuery(
                    query_id="q-original",
                    text="另一个问题",
                    purpose=QueryPurpose.ORIGINAL,
                    mode=RetrievalMode.AUTO,
                    weight=1.0,
                    target_sub_question_ids=[],
                )
            ],
            exact_terms=[],
            filters={},
            planner_version="multi-query.v1",
        )
    )

    with pytest.raises(ModelInvalidResponseError, match="original question"):
        await MultiQueryPlanner(model).plan(
            RetrievalPlanningRequest(
                original_question="原始问题",
                model_input="原始问题",
            )
        )


@pytest.mark.asyncio
async def test_multi_query_planner_enforces_exact_identifier_channel() -> None:
    question = "innodb_flush_log_at_trx_commit=0 时如何刷盘？"
    model = RecordingPlanningModel(
        RetrievalPlan(
            original_question=question,
            standalone_question=question,
            complexity=QueryComplexity.SIMPLE,
            sub_questions=[],
            queries=[
                PlannedQuery(
                    query_id="q-original",
                    text=question,
                    purpose=QueryPurpose.ORIGINAL,
                    mode=RetrievalMode.AUTO,
                    weight=0.6,
                    target_sub_question_ids=[],
                ),
                PlannedQuery(
                    query_id="q-semantic",
                    text="innodb_flush_log_at_trx_commit 0 刷盘机制",
                    purpose=QueryPurpose.SEMANTIC,
                    mode=RetrievalMode.HYBRID,
                    weight=1.0,
                    target_sub_question_ids=[],
                ),
            ],
            exact_terms=[],
            filters={},
            planner_version="multi-query.v1",
        )
    )

    result = await MultiQueryPlanner(model).plan(
        RetrievalPlanningRequest(original_question=question, model_input=question)
    )

    assert result.plan is not None
    assert result.plan.complexity is QueryComplexity.EXACT
    assert result.plan.exact_terms == ["innodb_flush_log_at_trx_commit", "0"]
    exact_query = next(
        query for query in result.plan.queries if query.purpose is QueryPurpose.EXACT
    )
    assert exact_query.mode is RetrievalMode.EXACT
    assert "innodb_flush_log_at_trx_commit" in exact_query.text


@pytest.mark.asyncio
async def test_multi_query_planner_repairs_unresolved_contextual_standalone_question() -> None:
    question = "那它是在什么时候创建的？"
    model = RecordingPlanningModel(
        RetrievalPlan(
            original_question=question,
            standalone_question=question,
            complexity=QueryComplexity.SIMPLE,
            sub_questions=[],
            queries=[
                PlannedQuery(
                    query_id="q-original",
                    text=question,
                    purpose=QueryPurpose.ORIGINAL,
                    mode=RetrievalMode.AUTO,
                    weight=0.6,
                    target_sub_question_ids=[],
                ),
                PlannedQuery(
                    query_id="q-semantic",
                    text="Read View 在 RC 和 RR 中何时创建？",
                    purpose=QueryPurpose.SEMANTIC,
                    mode=RetrievalMode.HYBRID,
                    weight=1.0,
                    target_sub_question_ids=[],
                ),
            ],
            exact_terms=[],
            filters={},
            planner_version="multi-query.v1",
        )
    )

    result = await MultiQueryPlanner(model).plan(
        RetrievalPlanningRequest(
            original_question=question,
            model_input="上一轮讨论 Read View。当前问题：" + question,
            conversation_context_present=True,
        )
    )

    assert result.plan is not None
    assert result.plan.standalone_question == "Read View 在 RC 和 RR 中何时创建？"


@pytest.mark.asyncio
async def test_multi_query_planner_accepts_keyword_context_resolution() -> None:
    question = "那后者记录的是物理页修改还是逻辑变更？"
    model = RecordingPlanningModel(
        RetrievalPlanDraft(
            original_question=question,
            standalone_question=question,
            complexity=QueryComplexity.SIMPLE,
            sub_questions=[],
            queries=[
                PlannedQuery(
                    query_id="q-original",
                    text=question,
                    purpose=QueryPurpose.ORIGINAL,
                    mode=RetrievalMode.AUTO,
                    weight=1.0,
                    target_sub_question_ids=[],
                ),
                PlannedQuery(
                    query_id="q-keyword",
                    text="binlog 记录 物理页修改 逻辑变更",
                    purpose=QueryPurpose.KEYWORD,
                    mode=RetrievalMode.KEYWORD,
                    weight=0.8,
                    target_sub_question_ids=[],
                ),
            ],
            exact_terms=["binlog"],
            filters={},
            planner_version="multi-query.v1",
        )
    )

    result = await MultiQueryPlanner(model).plan(
        RetrievalPlanningRequest(
            original_question=question,
            model_input="上一轮比较 redo log 和 binlog，后者指 binlog。" + question,
            conversation_context_present=True,
        )
    )

    assert result.plan is not None
    assert result.plan.standalone_question == "binlog 记录 物理页修改 逻辑变更"


@pytest.mark.asyncio
async def test_multi_query_planner_normalizes_exact_draft_without_exact_query() -> None:
    question = "PostgreSQL 的 VACUUM FULL 会怎样重写表？"
    model = RecordingPlanningModel(
        RetrievalPlanDraft(
            original_question=question,
            standalone_question=question,
            complexity=QueryComplexity.EXACT,
            sub_questions=[],
            queries=[
                PlannedQuery(
                    query_id="q-original",
                    text=question,
                    purpose=QueryPurpose.ORIGINAL,
                    mode=RetrievalMode.AUTO,
                    weight=0.6,
                    target_sub_question_ids=[],
                ),
                PlannedQuery(
                    query_id="q-semantic",
                    text="PostgreSQL VACUUM FULL 重写表机制",
                    purpose=QueryPurpose.SEMANTIC,
                    mode=RetrievalMode.HYBRID,
                    weight=1.0,
                    target_sub_question_ids=[],
                ),
            ],
            exact_terms=["VACUUM FULL"],
            filters={},
            planner_version="multi-query.v1",
        )
    )

    result = await MultiQueryPlanner(model).plan(
        RetrievalPlanningRequest(original_question=question, model_input=question)
    )

    assert result.plan is not None
    exact_query = next(
        query for query in result.plan.queries if query.purpose is QueryPurpose.EXACT
    )
    assert exact_query.mode is RetrievalMode.EXACT
    assert "VACUUM FULL" in exact_query.text


@pytest.mark.asyncio
async def test_multi_query_planner_normalizes_weights_and_duplicate_exact_queries() -> None:
    question = "EXPLAIN 的 rows 字段是精确行数吗？"
    model = RecordingPlanningModel(
        RetrievalPlanDraft(
            original_question=question,
            standalone_question=question,
            complexity=QueryComplexity.EXACT,
            sub_questions=[],
            queries=[
                PlannedQuery(
                    query_id="q-original",
                    text=question,
                    purpose=QueryPurpose.ORIGINAL,
                    mode=RetrievalMode.AUTO,
                    weight=1.0,
                    target_sub_question_ids=[],
                ),
                PlannedQuery(
                    query_id="q-exact",
                    text="EXPLAIN rows 精确行数",
                    purpose=QueryPurpose.EXACT,
                    mode=RetrievalMode.EXACT,
                    weight=0.8,
                    target_sub_question_ids=[],
                ),
                PlannedQuery(
                    query_id="q-keyword",
                    text="EXPLAIN rows 精确行数",
                    purpose=QueryPurpose.KEYWORD,
                    mode=RetrievalMode.KEYWORD,
                    weight=0.7,
                    target_sub_question_ids=[],
                ),
            ],
            exact_terms=["EXPLAIN", "rows"],
            filters={},
            planner_version="multi-query.v1",
        )
    )

    result = await MultiQueryPlanner(model).plan(
        RetrievalPlanningRequest(original_question=question, model_input=question)
    )

    assert result.plan is not None
    assert len(result.plan.queries) == 2
    original = next(
        query for query in result.plan.queries if query.purpose is QueryPurpose.ORIGINAL
    )
    assert original.weight == 0.6


@pytest.mark.asyncio
async def test_multi_query_planner_normalizes_simple_query_budget_and_references() -> None:
    question = "查一张大表总行数太慢，有什么常见办法？"
    model = RecordingPlanningModel(
        RetrievalPlanDraft(
            original_question=question,
            standalone_question=question,
            complexity=QueryComplexity.SIMPLE,
            sub_questions=[],
            queries=[
                PlannedQuery(
                    query_id="q1",
                    text=question,
                    purpose=QueryPurpose.ORIGINAL,
                    mode=RetrievalMode.AUTO,
                    weight=1.0,
                    target_sub_question_ids=[],
                ),
                PlannedQuery(
                    query_id="q1",
                    text="MySQL 大表 count 总行数 优化",
                    purpose=QueryPurpose.SEMANTIC,
                    mode=RetrievalMode.HYBRID,
                    weight=0.9,
                    target_sub_question_ids=["missing"],
                ),
                PlannedQuery(
                    query_id="q3",
                    text="大表总行数统计慢 常见方案",
                    purpose=QueryPurpose.KEYWORD,
                    mode=RetrievalMode.KEYWORD,
                    weight=0.7,
                    target_sub_question_ids=[],
                ),
            ],
            exact_terms=[],
            filters={},
            planner_version="multi-query.v1",
        )
    )

    result = await MultiQueryPlanner(model).plan(
        RetrievalPlanningRequest(original_question=question, model_input=question)
    )

    assert result.plan is not None
    assert len(result.plan.queries) == 2
    assert len({query.query_id for query in result.plan.queries}) == 2
    assert result.plan.queries[1].target_sub_question_ids == []
