import hashlib
import re
from dataclasses import dataclass

from yan_agent.domain.retrieval_routing import (
    RequestedRetrievalStrategy,
    RetrievalRoutingDecision,
    RetrievalRoutingRequest,
    RetrievalStrategy,
    RoutingReason,
)

ROUTING_VERSION = "adaptive-router.v1"

_LEADING_CONTEXT_REFERENCE = re.compile(r"^(?:那|它|这个|那个|后者|前者|这两个|这三种|如果那)")
_EXPLICIT_CONTEXT_REFERENCE = re.compile(r"(?:上一个|刚才|前面(?:说|提|讨论)的)")
_COLLOQUIAL = re.compile(r"(啥|咋|怎么救|到底|这玩意|是不是就等于|明明|有哪些坑)")
_SIMPLE_FORM = re.compile(
    r"^(?:什么是.+|什么叫.+|.+(?:主要负责|作用|职责|用途|含义|对象)是什么|"
    r".+默认使用什么|.+保存什么|.+等于哪两种|.+是哪种|.+有哪些)$"
)
_MULTI_DIMENSION = re.compile(
    r"(分别|各自|同时|以及|多个方面|哪些.*分别|"
    r"在.+[、和].+(?:方面|上).*(?:区别|差异|关系)|"
    r"结合.+(?:和|、).+(?:分析|优化|选择|判断|继续))"
)
_COMPOUND_CLAUSE = re.compile(r"(?:，|；|;).*(?:如何|为什么|怎样|有什么关系|应该如何|是否)")
_MULTI_VALUE_COMPARISON = re.compile(
    r"(?:\d+、){1,}\d+.*(?:差异|区别)|(?:三种|四种|多种).*(?:差异|区别)"
)


@dataclass(frozen=True)
class AdaptiveRoutingPolicy:
    shadow_mode: bool = True
    raw_query_enabled: bool = True
    raw_query_rollout_percent: int = 100
    multi_query_enabled: bool = False
    multi_query_rollout_percent: int = 0
    rollout_salt: str = ROUTING_VERSION
    minimum_multi_query_model_calls: int = 1
    minimum_multi_query_seconds: float = 20

    def __post_init__(self) -> None:
        if not 0 <= self.raw_query_rollout_percent <= 100:
            raise ValueError("raw_query_rollout_percent must be between 0 and 100")
        if not 0 <= self.multi_query_rollout_percent <= 100:
            raise ValueError("multi_query_rollout_percent must be between 0 and 100")
        if not self.rollout_salt:
            raise ValueError("rollout_salt must not be blank")
        if self.minimum_multi_query_model_calls < 0:
            raise ValueError("minimum_multi_query_model_calls must not be negative")
        if self.minimum_multi_query_seconds < 0:
            raise ValueError("minimum_multi_query_seconds must not be negative")


class AdaptiveRetrievalRouter:
    def __init__(self, policy: AdaptiveRoutingPolicy | None = None) -> None:
        self._policy = policy or AdaptiveRoutingPolicy()

    def route(self, request: RetrievalRoutingRequest) -> RetrievalRoutingDecision:
        selected, reasons = self._select_candidate(request)
        return self.route_candidate(request, selected, reasons)

    def route_candidate(
        self,
        request: RetrievalRoutingRequest,
        selected_strategy: RetrievalStrategy,
        routing_reasons: list[RoutingReason],
    ) -> RetrievalRoutingDecision:
        selected = selected_strategy
        reasons = list(routing_reasons)
        rollout_eligible = False
        effective = selected
        fallback_reason: RoutingReason | None = None

        if selected is RetrievalStrategy.RAW_QUERY:
            rollout_eligible = self._is_rollout_eligible(
                request.routing_key,
                self._policy.raw_query_rollout_percent,
            )
            if not self._policy.raw_query_enabled:
                fallback_reason = RoutingReason.RAW_QUERY_DISABLED
            elif not rollout_eligible:
                fallback_reason = RoutingReason.RAW_QUERY_OUTSIDE_ROLLOUT
            if fallback_reason is not None:
                effective = RetrievalStrategy.SINGLE_REWRITE

        if selected is RetrievalStrategy.MULTI_QUERY:
            rollout_eligible = self._is_rollout_eligible(
                request.routing_key,
                self._policy.multi_query_rollout_percent,
            )
            fallback_reason = self._multi_query_gate(request, rollout_eligible)
            if fallback_reason is not None:
                effective = RetrievalStrategy.SINGLE_REWRITE

        if self._policy.shadow_mode and effective is not RetrievalStrategy.SINGLE_REWRITE:
            effective = RetrievalStrategy.SINGLE_REWRITE
            fallback_reason = RoutingReason.SHADOW_MODE

        if fallback_reason is not None:
            reasons.append(fallback_reason)

        return RetrievalRoutingDecision(
            requested_strategy=request.requested_strategy,
            selected_strategy=selected,
            effective_strategy=effective,
            routing_reasons=_unique(reasons),
            routing_version=ROUTING_VERSION,
            rollout_eligible=rollout_eligible,
            fallback_from=selected if effective is not selected else None,
            fallback_reason=fallback_reason if effective is not selected else None,
        )

    def _select_candidate(
        self,
        request: RetrievalRoutingRequest,
    ) -> tuple[RetrievalStrategy, list[RoutingReason]]:
        if request.requested_strategy is not RequestedRetrievalStrategy.AUTO:
            return RetrievalStrategy(request.requested_strategy.value), [
                RoutingReason.SINGLE_INFORMATION_NEED
            ]

        question = _normalize_question(request.question)
        context_dependent = request.conversation_context_present or bool(
            _LEADING_CONTEXT_REFERENCE.search(question)
            or _EXPLICIT_CONTEXT_REFERENCE.search(question)
        )
        if context_dependent:
            return RetrievalStrategy.SINGLE_REWRITE, [RoutingReason.CONTEXT_DEPENDENT]

        complex_reasons = _complex_reasons(question)
        if complex_reasons:
            return RetrievalStrategy.MULTI_QUERY, complex_reasons

        if _is_high_confidence_simple(question):
            reasons = [RoutingReason.HIGH_CONFIDENCE_SIMPLE]
            if _contains_exact_identifier(question):
                reasons.append(RoutingReason.EXACT_IDENTIFIER)
            return RetrievalStrategy.RAW_QUERY, reasons

        reasons = [RoutingReason.SINGLE_INFORMATION_NEED]
        if _COLLOQUIAL.search(question):
            reasons.append(RoutingReason.COLLOQUIAL_OR_AMBIGUOUS)
        if _contains_exact_identifier(question):
            reasons.append(RoutingReason.EXACT_IDENTIFIER)
        return RetrievalStrategy.SINGLE_REWRITE, reasons

    def _multi_query_gate(
        self,
        request: RetrievalRoutingRequest,
        rollout_eligible: bool,
    ) -> RoutingReason | None:
        if not self._policy.multi_query_enabled:
            return RoutingReason.MULTI_QUERY_DISABLED
        if not request.dependencies_healthy:
            return RoutingReason.DEPENDENCY_UNHEALTHY
        if (
            request.remaining_model_calls < self._policy.minimum_multi_query_model_calls
            or request.remaining_seconds < self._policy.minimum_multi_query_seconds
        ):
            return RoutingReason.BUDGET_INSUFFICIENT
        if not rollout_eligible:
            return RoutingReason.OUTSIDE_ROLLOUT
        return None

    def _is_rollout_eligible(self, routing_key: str, rollout_percent: int) -> bool:
        if rollout_percent == 0:
            return False
        if rollout_percent == 100:
            return True
        digest = hashlib.sha256(f"{self._policy.rollout_salt}:{routing_key}".encode()).digest()
        bucket = int.from_bytes(digest[:8], "big") % 100
        return bucket < rollout_percent


def _normalize_question(question: str) -> str:
    return " ".join(question.strip().split()).rstrip("。！？?!")


def _complex_reasons(question: str) -> list[RoutingReason]:
    reasons: list[RoutingReason] = []
    if _MULTI_DIMENSION.search(question) or question.count("？") + question.count("?") > 1:
        reasons.append(RoutingReason.MULTI_SUBQUESTION)
    if _MULTI_DIMENSION.search(question):
        reasons.append(RoutingReason.MULTI_CONSTRAINT)
    if _COMPOUND_CLAUSE.search(question):
        reasons.append(RoutingReason.MULTI_SUBQUESTION)
    if _MULTI_VALUE_COMPARISON.search(question):
        reasons.extend([RoutingReason.MULTI_SUBQUESTION, RoutingReason.MULTI_CONSTRAINT])
    if "区别" in question or "差异" in question or "对比" in question:
        if _MULTI_DIMENSION.search(question):
            reasons.append(RoutingReason.COMPARISON)
    return _unique(reasons)


def _is_high_confidence_simple(question: str) -> bool:
    if len(question) > 48 or "，" in question or "," in question or "；" in question:
        return False
    if _COLLOQUIAL.search(question):
        return False
    return bool(_SIMPLE_FORM.fullmatch(question))


def _contains_exact_identifier(question: str) -> bool:
    return bool(
        re.search(r"`[^`]+`|[A-Za-z][A-Za-z0-9_]*(?:_[A-Za-z0-9_]+)+|\w+\s*=\s*\d+", question)
    )


def _unique(values: list[RoutingReason]) -> list[RoutingReason]:
    return list(dict.fromkeys(values))
