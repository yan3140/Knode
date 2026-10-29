from prometheus_client import CollectorRegistry, Counter, Histogram, generate_latest

from yan_agent.domain.retrieval_routing import RetrievalRoutingDecision, RetrievalStrategy

_LATENCY_BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120)


class AgentMetrics:
    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry(auto_describe=True)
        self.http_requests = Counter(
            "yan_agent_http_requests_total",
            "HTTP requests handled by route and status class.",
            ("method", "route", "status_class"),
            registry=self.registry,
        )
        self.http_duration = Histogram(
            "yan_agent_http_request_duration_seconds",
            "End-to-end HTTP request duration.",
            ("method", "route"),
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )
        self.runs = Counter(
            "yan_agent_runs_total",
            "Agent runs by terminal outcome.",
            ("status", "degraded", "effective_strategy"),
            registry=self.registry,
        )
        self.run_duration = Histogram(
            "yan_agent_run_duration_seconds",
            "Agent worker run duration.",
            ("status", "effective_strategy"),
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )
        self.graph_node_duration = Histogram(
            "yan_agent_graph_node_duration_seconds",
            "LangGraph node duration by outcome.",
            ("node", "outcome"),
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )
        self.dependency_requests = Counter(
            "yan_agent_dependency_requests_total",
            "External dependency requests by outcome.",
            ("dependency", "operation", "outcome"),
            registry=self.registry,
        )
        self.dependency_duration = Histogram(
            "yan_agent_dependency_duration_seconds",
            "External dependency request duration.",
            ("dependency", "operation"),
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )
        self.model_tokens = Counter(
            "yan_agent_model_tokens_total",
            "Model tokens reported by the provider.",
            ("direction",),
            registry=self.registry,
        )
        self.model_estimated_cost = Counter(
            "yan_agent_model_estimated_cost_usd_total",
            "Estimated model cost in US dollars using configured rates.",
            registry=self.registry,
        )
        self.context_loads = Counter(
            "yan_agent_context_loads_total",
            "Conversation context loads by cache tier and outcome.",
            ("tier", "outcome"),
            registry=self.registry,
        )
        self.context_rebuild_duration = Histogram(
            "yan_agent_context_rebuild_duration_seconds",
            "PostgreSQL context rebuild duration.",
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )
        self.context_version_conflicts = Counter(
            "yan_agent_context_version_conflicts_total",
            "Cached contexts rejected because their version was stale.",
            registry=self.registry,
        )
        self.routing_decisions = Counter(
            "yan_agent_routing_decisions_total",
            "Adaptive retrieval routing decisions by bounded outcome.",
            (
                "selected_strategy",
                "effective_strategy",
                "fallback_reason",
                "routing_version",
            ),
            registry=self.registry,
        )

    def observe_http(self, method: str, route: str, status_code: int, duration: float) -> None:
        self.http_requests.labels(method, route, f"{status_code // 100}xx").inc()
        self.http_duration.labels(method, route).observe(duration)

    def observe_run(
        self,
        status: str,
        degraded: bool,
        duration: float,
        effective_strategy: RetrievalStrategy | None = None,
    ) -> None:
        strategy = effective_strategy.value if effective_strategy is not None else "UNKNOWN"
        self.runs.labels(status, str(degraded).lower(), strategy).inc()
        self.run_duration.labels(status, strategy).observe(duration)

    def observe_graph_node(self, node: str, outcome: str, duration: float) -> None:
        self.graph_node_duration.labels(node, outcome).observe(duration)

    def observe_dependency(
        self,
        dependency: str,
        operation: str,
        outcome: str,
        duration: float,
    ) -> None:
        self.dependency_requests.labels(dependency, operation, outcome).inc()
        self.dependency_duration.labels(dependency, operation).observe(duration)

    def observe_context(self, tier: str, outcome: str) -> None:
        self.context_loads.labels(tier, outcome).inc()

    def observe_model_usage(
        self,
        input_tokens: int,
        output_tokens: int,
        estimated_cost_usd: float,
    ) -> None:
        self.model_tokens.labels("input").inc(input_tokens)
        self.model_tokens.labels("output").inc(output_tokens)
        self.model_estimated_cost.inc(estimated_cost_usd)

    def observe_context_rebuild(self, duration: float) -> None:
        self.context_rebuild_duration.observe(duration)

    def observe_context_version_conflict(self) -> None:
        self.context_version_conflicts.inc()

    def observe_routing(self, decision: RetrievalRoutingDecision) -> None:
        self.routing_decisions.labels(
            decision.selected_strategy.value,
            decision.effective_strategy.value,
            decision.fallback_reason.value if decision.fallback_reason else "NONE",
            decision.routing_version,
        ).inc()

    def render(self) -> bytes:
        return generate_latest(self.registry)
