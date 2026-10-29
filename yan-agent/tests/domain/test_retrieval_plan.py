import pytest
from pydantic import ValidationError

from yan_agent.domain.qa import RetrievalMode
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    RetrievalPlan,
    SubQuestion,
)


def make_plan(**updates: object) -> RetrievalPlan:
    values: dict[str, object] = {
        "original_question": "联合索引为什么会失效？",
        "standalone_question": "MySQL 联合索引失效的原因是什么？",
        "complexity": QueryComplexity.SIMPLE,
        "sub_questions": [],
        "queries": [
            PlannedQuery(
                query_id="q-original",
                text="联合索引为什么会失效？",
                purpose=QueryPurpose.ORIGINAL,
                mode=RetrievalMode.AUTO,
                weight=0.6,
                target_sub_question_ids=[],
            ),
            PlannedQuery(
                query_id="q-semantic",
                text="MySQL 联合索引失效的原因",
                purpose=QueryPurpose.SEMANTIC,
                mode=RetrievalMode.HYBRID,
                weight=1.0,
                target_sub_question_ids=[],
            ),
        ],
        "exact_terms": [],
        "filters": {},
        "planner_version": "multi-query.v1",
    }
    values.update(updates)
    return RetrievalPlan.model_validate(values)


def test_simple_plan_accepts_one_or_two_queries_and_original_channel() -> None:
    plan = make_plan()

    assert len(plan.queries) == 2
    assert plan.queries[0].purpose is QueryPurpose.ORIGINAL


def test_plan_rejects_more_than_four_queries() -> None:
    query = PlannedQuery(
        query_id="placeholder",
        text="query",
        purpose=QueryPurpose.SEMANTIC,
        mode=RetrievalMode.AUTO,
        weight=1.0,
        target_sub_question_ids=[],
    )
    queries = [
        query.model_copy(update={"query_id": f"q-{index}", "text": f"query {index}"})
        for index in range(5)
    ]

    with pytest.raises(ValidationError, match="at most 4"):
        make_plan(complexity=QueryComplexity.COMPOUND, queries=queries)


def test_simple_plan_rejects_unnecessary_query_expansion() -> None:
    queries = [
        PlannedQuery(
            query_id=f"q-{index}",
            text="联合索引为什么会失效？" if index == 0 else f"query {index}",
            purpose=QueryPurpose.ORIGINAL if index == 0 else QueryPurpose.SEMANTIC,
            mode=RetrievalMode.AUTO,
            weight=1.0,
            target_sub_question_ids=[],
        )
        for index in range(3)
    ]

    with pytest.raises(ValidationError, match="SIMPLE plans may contain at most 2"):
        make_plan(queries=queries)


def test_plan_rejects_unknown_sub_question_reference() -> None:
    query = PlannedQuery(
        query_id="q-original",
        text="事务隔离级别和 MVCC 的关系",
        purpose=QueryPurpose.ORIGINAL,
        mode=RetrievalMode.AUTO,
        weight=1.0,
        target_sub_question_ids=["sq-missing"],
    )

    with pytest.raises(ValidationError, match="unknown sub-question"):
        make_plan(
            complexity=QueryComplexity.COMPOUND,
            sub_questions=[SubQuestion(sub_question_id="sq-1", text="事务隔离级别")],
            queries=[query],
        )


def test_exact_plan_requires_and_preserves_exact_terms() -> None:
    original_query = PlannedQuery(
        query_id="q-original",
        text="innodb_flush_log_at_trx_commit 参数含义",
        purpose=QueryPurpose.ORIGINAL,
        mode=RetrievalMode.AUTO,
        weight=0.6,
        target_sub_question_ids=[],
    )
    exact_query = PlannedQuery(
        query_id="q-exact",
        text="精确查找 innodb_flush_log_at_trx_commit 参数含义",
        purpose=QueryPurpose.EXACT,
        mode=RetrievalMode.EXACT,
        weight=1.0,
        target_sub_question_ids=[],
    )

    with pytest.raises(ValidationError, match="preserve exact term"):
        make_plan(
            original_question="innodb_flush_log_at_trx_commit 参数含义",
            standalone_question="InnoDB 日志提交参数含义",
            complexity=QueryComplexity.EXACT,
            queries=[
                original_query,
                exact_query.model_copy(update={"text": "InnoDB 日志提交参数含义"}),
            ],
            exact_terms=["missing_identifier"],
        )

    plan = make_plan(
        original_question="innodb_flush_log_at_trx_commit 参数含义",
        standalone_question="innodb_flush_log_at_trx_commit 参数含义",
        complexity=QueryComplexity.EXACT,
        queries=[original_query, exact_query],
        exact_terms=["innodb_flush_log_at_trx_commit"],
    )
    assert plan.exact_terms == ["innodb_flush_log_at_trx_commit"]


def test_plan_requires_an_original_recall_channel() -> None:
    semantic = PlannedQuery(
        query_id="q-semantic",
        text="MySQL 联合索引失效的原因",
        purpose=QueryPurpose.SEMANTIC,
        mode=RetrievalMode.HYBRID,
        weight=1.0,
        target_sub_question_ids=[],
    )

    with pytest.raises(ValidationError, match="ORIGINAL query"):
        make_plan(queries=[semantic])
