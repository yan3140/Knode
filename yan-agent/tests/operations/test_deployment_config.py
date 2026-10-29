from pathlib import Path

import yaml

from yan_agent.operations.rollout_gate import ManualReviewEvidence, RolloutStage

PROJECT_ROOT = Path(__file__).parents[3]


def load_yaml(path: Path) -> dict[str, object]:
    payload = yaml.safe_load(path.read_text(encoding="utf-8"))
    assert isinstance(payload, dict)
    return payload


def load_env(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def test_compose_separates_secrets_and_hardens_agent_containers() -> None:
    compose_path = PROJECT_ROOT / "compose.agent.yml"
    raw = compose_path.read_text(encoding="utf-8")
    compose = load_yaml(compose_path)
    services = compose["services"]
    assert isinstance(services, dict)
    api = services["yan-agent"]
    worker = services["yan-agent-worker"]
    assert isinstance(api, dict)
    assert isinstance(worker, dict)

    assert "local-agent-service-key-change-me" not in raw
    assert "local-principal-jwt-secret-change-me" not in raw
    assert "YAN_AGENT_MODEL_API_KEY" not in api["environment"]
    assert "YAN_AGENT_JAVA_SERVICE_KEY" not in api["environment"]
    assert "YAN_AGENT_PRINCIPAL_JWT_SECRET" not in worker["environment"]
    assert api["read_only"] is True
    assert worker["read_only"] is True
    assert api["cap_drop"] == ["ALL"]
    assert worker["cap_drop"] == ["ALL"]
    assert worker["extra_hosts"] == ["host.docker.internal:host-gateway"]


def test_observability_stack_is_pinned_and_scrapes_api_and_worker() -> None:
    compose = load_yaml(PROJECT_ROOT / "compose.agent.yml")
    services = compose["services"]
    assert isinstance(services, dict)
    assert services["agent-otel-collector"]["image"] == ("otel/opentelemetry-collector:0.157.0")
    assert services["agent-jaeger"]["image"] == "jaegertracing/jaeger:2.20.0"
    assert services["agent-prometheus"]["image"] == "prom/prometheus:v3.13.2"

    collector = load_yaml(PROJECT_ROOT / "deploy" / "agent" / "otel-collector.yml")
    traces = collector["service"]["pipelines"]["traces"]
    assert traces["receivers"] == ["otlp"]
    assert traces["exporters"] == ["otlp_grpc/jaeger"]

    prometheus = load_yaml(PROJECT_ROOT / "deploy" / "agent" / "prometheus.yml")
    jobs = {item["job_name"]: item for item in prometheus["scrape_configs"]}
    assert jobs["yan-agent-api"]["static_configs"][0]["targets"] == ["yan-agent:8990"]
    assert jobs["yan-agent-worker"]["static_configs"][0]["targets"] == ["yan-agent-worker:8991"]


def test_local_prometheus_scrapes_local_processes_without_compose() -> None:
    prometheus = load_yaml(PROJECT_ROOT / "deploy" / "agent" / "prometheus.local.yml")
    jobs = {item["job_name"]: item for item in prometheus["scrape_configs"]}

    assert prometheus["global"]["scrape_interval"] == "10s"
    assert prometheus["rule_files"] == ["agent-alerts.yml"]
    assert jobs["yan-agent-api"]["static_configs"][0]["targets"] == ["127.0.0.1:8990"]
    assert jobs["yan-agent-worker"]["static_configs"][0]["targets"] == ["127.0.0.1:8991"]


def test_dockerfile_uses_multi_stage_non_root_runtime() -> None:
    dockerfile = (PROJECT_ROOT / "yan-agent" / "Dockerfile").read_text(encoding="utf-8")
    assert "AS builder" in dockerfile
    assert "AS runtime" in dockerfile
    assert "USER 10001:10001" in dockerfile
    assert "COPY --from=builder --chown=agent:agent /app /app" in dockerfile


def test_stage55f_production_defaults_are_shadow_only() -> None:
    values = load_env(PROJECT_ROOT / "deploy" / "agent" / "production.env.example")

    assert values["YAN_AGENT_ADAPTIVE_ROUTING_ENABLED"] == "true"
    assert values["YAN_AGENT_ADAPTIVE_ROUTING_SHADOW_MODE"] == "true"
    assert values["YAN_AGENT_ADAPTIVE_EXECUTION_ENABLED"] == "false"
    assert values["YAN_AGENT_RAW_QUERY_ENABLED"] == "false"
    assert values["YAN_AGENT_RAW_QUERY_ROLLOUT_PERCENT"] == "0"
    assert values["YAN_AGENT_MULTI_QUERY_ENABLED"] == "false"
    assert values["YAN_AGENT_MULTI_QUERY_ROLLOUT_PERCENT"] == "0"
    assert values["YAN_AGENT_MULTI_QUERY_QUALITY_GATE_PASSED"] == "false"


def test_stage55f_rollout_overlays_expand_a_monotonically_and_keep_c_blocked() -> None:
    rollout_dir = PROJECT_ROOT / "deploy" / "agent" / "rollout"
    stages = {
        name: load_env(rollout_dir / f"stage55f-{name}.env") for name in ("a10", "a50", "a100")
    }

    assert [stages[name]["YAN_AGENT_RAW_QUERY_ROLLOUT_PERCENT"] for name in stages] == [
        "10",
        "50",
        "100",
    ]
    assert len({stage["YAN_AGENT_ROUTING_ROLLOUT_SALT"] for stage in stages.values()}) == 1
    for stage in stages.values():
        assert stage["YAN_AGENT_ADAPTIVE_ROUTING_SHADOW_MODE"] == "false"
        assert stage["YAN_AGENT_ADAPTIVE_EXECUTION_ENABLED"] == "true"
        assert stage["YAN_AGENT_RAW_QUERY_ENABLED"] == "true"
        assert stage["YAN_AGENT_MULTI_QUERY_ENABLED"] == "false"
        assert stage["YAN_AGENT_MULTI_QUERY_ROLLOUT_PERCENT"] == "0"
        assert stage["YAN_AGENT_MULTI_QUERY_QUALITY_GATE_PASSED"] == "false"


def test_stage55f_rollback_overlay_restores_fixed_b() -> None:
    values = load_env(PROJECT_ROOT / "deploy" / "agent" / "rollout" / "stage55f-rollback-b.env")

    assert values["YAN_AGENT_ADAPTIVE_ROUTING_SHADOW_MODE"] == "true"
    assert values["YAN_AGENT_ADAPTIVE_EXECUTION_ENABLED"] == "false"
    assert values["YAN_AGENT_RAW_QUERY_ENABLED"] == "false"
    assert values["YAN_AGENT_RAW_QUERY_ROLLOUT_PERCENT"] == "0"
    assert values["YAN_AGENT_MULTI_QUERY_ENABLED"] == "false"
    assert values["YAN_AGENT_MULTI_QUERY_ROLLOUT_PERCENT"] == "0"


def test_stage55f_manual_review_example_is_valid_but_cannot_pass_by_default() -> None:
    path = PROJECT_ROOT / "deploy" / "agent" / "rollout" / "manual-review-a10.example.json"

    evidence = ManualReviewEvidence.model_validate_json(path.read_text(encoding="utf-8"))

    assert evidence.stage is RolloutStage.A10
    assert evidence.reviewed_answers == 0


def test_stage55f_alerts_can_rollback_to_fixed_b() -> None:
    alerts = load_yaml(PROJECT_ROOT / "deploy" / "agent" / "agent-alerts.yml")
    rules = alerts["groups"][0]["rules"]
    by_name = {rule["alert"]: rule for rule in rules}

    assert by_name["YanAgentPlannerFallbackRateHigh"]["labels"]["rollout_action"] == (
        "rollback_to_b"
    )
    assert by_name["YanAgentAdaptiveRunP95High"]["labels"]["rollout_action"] == ("rollback_to_b")
    assert by_name["YanAgentFastPathNonSuccessRateHigh"]["labels"]["rollout_action"] == (
        "rollback_to_b"
    )
    assert by_name["YanAgentFastPathP95Regression"]["labels"]["rollout_action"] == ("rollback_to_b")
    assert by_name["YanAgentMultiQueryUnexpectedlyActive"]["labels"]["severity"] == ("critical")
