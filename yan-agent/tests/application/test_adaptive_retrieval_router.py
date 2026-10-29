from yan_agent.application.adaptive_retrieval_router import (
    AdaptiveRetrievalRouter,
    AdaptiveRoutingPolicy,
)
from yan_agent.domain.retrieval_planning import RetrievalStrategy
from yan_agent.domain.retrieval_routing import (
    RequestedRetrievalStrategy,
    RetrievalRoutingRequest,
    RoutingReason,
)
from yan_agent.observability.metrics import AgentMetrics


def request(
    question: str,
    *,
    context_present: bool = False,
    routing_key: str = "user-7:kb-3",
    remaining_model_calls: int = 8,
    remaining_seconds: float = 90,
    dependencies_healthy: bool = True,
) -> RetrievalRoutingRequest:
    return RetrievalRoutingRequest(
        question=question,
        conversation_context_present=context_present,
        routing_key=routing_key,
        requested_strategy=RequestedRetrievalStrategy.AUTO,
        remaining_model_calls=remaining_model_calls,
        remaining_seconds=remaining_seconds,
        dependencies_healthy=dependencies_healthy,
    )


def test_complete_simple_fact_uses_zero_model_call_fast_path() -> None:
    router = AdaptiveRetrievalRouter(AdaptiveRoutingPolicy(shadow_mode=False))

    decision = router.route(request("什么是聚簇索引？"))

    assert decision.selected_strategy is RetrievalStrategy.RAW_QUERY
    assert decision.effective_strategy is RetrievalStrategy.RAW_QUERY
    assert RoutingReason.HIGH_CONFIDENCE_SIMPLE in decision.routing_reasons


def test_exact_identifier_does_not_make_a_mechanism_question_simple() -> None:
    router = AdaptiveRetrievalRouter(AdaptiveRoutingPolicy(shadow_mode=False))

    decision = router.route(request("Spring 事务传播行为 REQUIRES_NEW 如何挂起外层事务？"))

    assert decision.selected_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert RoutingReason.EXACT_IDENTIFIER in decision.routing_reasons


def test_compact_exact_field_mapping_is_multi_query_before_simple_fast_path() -> None:
    router = AdaptiveRetrievalRouter(
        AdaptiveRoutingPolicy(
            shadow_mode=False,
            multi_query_enabled=True,
            multi_query_rollout_percent=100,
        )
    )

    decision = router.route(request("DB_TRX_ID 和 DB_ROLL_PTR 分别保存什么？"))

    assert decision.selected_strategy is RetrievalStrategy.MULTI_QUERY
    assert RoutingReason.MULTI_SUBQUESTION in decision.routing_reasons


def test_multiple_exact_configuration_values_are_a_multi_query_candidate() -> None:
    router = AdaptiveRetrievalRouter(
        AdaptiveRoutingPolicy(
            shadow_mode=False,
            multi_query_enabled=True,
            multi_query_rollout_percent=100,
        )
    )

    decision = router.route(request("innodb_flush_log_at_trx_commit 的 0、1、2 三种配置有何差异？"))

    assert decision.selected_strategy is RetrievalStrategy.MULTI_QUERY
    assert RoutingReason.MULTI_CONSTRAINT in decision.routing_reasons


def test_context_dependent_question_defaults_to_single_rewrite() -> None:
    router = AdaptiveRetrievalRouter(AdaptiveRoutingPolicy(shadow_mode=False))

    decision = router.route(request("那它是在什么时候创建的？", context_present=True))

    assert decision.effective_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert RoutingReason.CONTEXT_DEPENDENT in decision.routing_reasons


def test_complex_question_uses_multi_query_when_all_gates_pass() -> None:
    router = AdaptiveRetrievalRouter(
        AdaptiveRoutingPolicy(
            shadow_mode=False,
            multi_query_enabled=True,
            multi_query_rollout_percent=100,
        )
    )

    decision = router.route(request("MVCC 如何实现可重复读，它和幻读、间隙锁分别有什么关系？"))

    assert decision.selected_strategy is RetrievalStrategy.MULTI_QUERY
    assert decision.effective_strategy is RetrievalStrategy.MULTI_QUERY
    assert RoutingReason.MULTI_SUBQUESTION in decision.routing_reasons
    assert decision.rollout_eligible is True


def test_multi_query_candidate_falls_back_to_b_when_feature_is_disabled() -> None:
    router = AdaptiveRetrievalRouter(
        AdaptiveRoutingPolicy(
            shadow_mode=False,
            multi_query_enabled=False,
            multi_query_rollout_percent=100,
        )
    )

    decision = router.route(request("redo log 和 binlog 在层次、内容和写入方式上有什么区别？"))

    assert decision.selected_strategy is RetrievalStrategy.MULTI_QUERY
    assert decision.effective_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert decision.fallback_from is RetrievalStrategy.MULTI_QUERY
    assert decision.fallback_reason is RoutingReason.MULTI_QUERY_DISABLED


def test_multi_query_candidate_falls_back_to_b_when_budget_is_insufficient() -> None:
    router = AdaptiveRetrievalRouter(
        AdaptiveRoutingPolicy(
            shadow_mode=False,
            multi_query_enabled=True,
            multi_query_rollout_percent=100,
            minimum_multi_query_seconds=20,
        )
    )

    decision = router.route(
        request(
            "分析 EXPLAIN 后应该如何结合索引设计和 SQL 改写继续优化？",
            remaining_model_calls=0,
            remaining_seconds=10,
        )
    )

    assert decision.effective_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert RoutingReason.BUDGET_INSUFFICIENT in decision.routing_reasons


def test_multi_query_candidate_falls_back_to_b_when_dependency_is_unhealthy() -> None:
    router = AdaptiveRetrievalRouter(
        AdaptiveRoutingPolicy(
            shadow_mode=False,
            multi_query_enabled=True,
            multi_query_rollout_percent=100,
        )
    )

    decision = router.route(
        request(
            "MVCC 如何实现可重复读，它和幻读、间隙锁分别有什么关系？",
            dependencies_healthy=False,
        )
    )

    assert decision.effective_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert decision.fallback_reason is RoutingReason.DEPENDENCY_UNHEALTHY


def test_rollout_assignment_is_stable_for_the_same_routing_key() -> None:
    router = AdaptiveRetrievalRouter(
        AdaptiveRoutingPolicy(
            shadow_mode=False,
            multi_query_enabled=True,
            multi_query_rollout_percent=37,
            rollout_salt="adaptive-router.v1-test",
        )
    )
    candidate = request(
        "聚簇索引和非聚簇索引有什么区别，查询与写入成本如何？",
        routing_key="stable-user:stable-kb",
    )

    decisions = [router.route(candidate) for _ in range(10)]

    assert len({item.rollout_eligible for item in decisions}) == 1
    assert len({item.effective_strategy for item in decisions}) == 1


def test_raw_query_candidate_outside_rollout_falls_back_to_b_with_audit_context() -> None:
    router = AdaptiveRetrievalRouter(
        AdaptiveRoutingPolicy(
            shadow_mode=False,
            raw_query_enabled=True,
            raw_query_rollout_percent=0,
        )
    )

    decision = router.route(request("什么是聚簇索引？"))

    assert decision.selected_strategy is RetrievalStrategy.RAW_QUERY
    assert decision.effective_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert decision.rollout_eligible is False
    assert decision.fallback_from is RetrievalStrategy.RAW_QUERY
    assert decision.fallback_reason is RoutingReason.RAW_QUERY_OUTSIDE_ROLLOUT


def test_raw_query_rollout_expands_monotonically_from_10_to_50_to_100_percent() -> None:
    def eligible_keys(percent: int) -> set[str]:
        router = AdaptiveRetrievalRouter(
            AdaptiveRoutingPolicy(
                shadow_mode=False,
                raw_query_enabled=True,
                raw_query_rollout_percent=percent,
                rollout_salt="stage55f-release-1",
            )
        )
        return {
            routing_key
            for index in range(1000)
            if (routing_key := f"user-{index}:kb-3")
            and router.route(
                request("什么是聚簇索引？", routing_key=routing_key)
            ).effective_strategy
            is RetrievalStrategy.RAW_QUERY
        }

    at_10 = eligible_keys(10)
    at_50 = eligible_keys(50)
    at_100 = eligible_keys(100)

    assert at_10 < at_50 < at_100
    assert 70 <= len(at_10) <= 130
    assert 450 <= len(at_50) <= 550
    assert len(at_100) == 1000


def test_shadow_mode_records_recommendation_but_keeps_effective_strategy_b() -> None:
    router = AdaptiveRetrievalRouter(AdaptiveRoutingPolicy(shadow_mode=True))

    decision = router.route(request("什么是聚簇索引？"))

    assert decision.selected_strategy is RetrievalStrategy.RAW_QUERY
    assert decision.effective_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert decision.fallback_reason is RoutingReason.SHADOW_MODE


def test_routing_metrics_use_only_bounded_strategy_and_reason_labels() -> None:
    metrics = AgentMetrics()
    decision = AdaptiveRetrievalRouter(AdaptiveRoutingPolicy(shadow_mode=True)).route(
        request("什么是聚簇索引？", routing_key="private-user-id")
    )

    metrics.observe_routing(decision)
    payload = metrics.render().decode()

    assert 'selected_strategy="RAW_QUERY"' in payload
    assert 'effective_strategy="SINGLE_REWRITE"' in payload
    assert "private-user-id" not in payload


def test_run_metrics_can_be_compared_by_effective_strategy() -> None:
    metrics = AgentMetrics()

    metrics.observe_run("SUCCEEDED", False, 0.25, RetrievalStrategy.RAW_QUERY)
    metrics.observe_run("SUCCEEDED", False, 1.25, RetrievalStrategy.SINGLE_REWRITE)

    payload = metrics.render().decode()
    assert 'effective_strategy="RAW_QUERY"' in payload
    assert 'effective_strategy="SINGLE_REWRITE"' in payload
