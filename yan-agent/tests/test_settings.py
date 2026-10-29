import pytest

from yan_agent.settings import Settings


def test_postgres_and_redis_configuration_are_independent(monkeypatch) -> None:
    monkeypatch.setenv(
        "YAN_AGENT_POSTGRES_DSN",
        "postgresql://agent:secret@postgres.example:5432/agent",
    )
    monkeypatch.setenv("YAN_AGENT_REDIS_URL", "redis://redis.example:6379/7")

    settings = Settings()

    assert settings.postgres_dsn == "postgresql://agent:secret@postgres.example:5432/agent"
    assert settings.sqlalchemy_dsn == (
        "postgresql+psycopg://agent:secret@postgres.example:5432/agent"
    )
    assert settings.redis_url == "redis://redis.example:6379/7"


def test_model_api_key_accepts_openai_standard_environment_variable(monkeypatch) -> None:
    monkeypatch.delenv("YAN_AGENT_MODEL_API_KEY", raising=False)
    monkeypatch.setenv("OPENAI_API_KEY", "openai-standard-key")

    settings = Settings(_env_file=None)

    assert settings.model_api_key.get_secret_value() == "openai-standard-key"


def test_project_model_api_key_takes_precedence_over_openai_alias(monkeypatch) -> None:
    monkeypatch.setenv("YAN_AGENT_MODEL_API_KEY", "project-specific-key")
    monkeypatch.setenv("OPENAI_API_KEY", "openai-standard-key")

    settings = Settings(_env_file=None)

    assert settings.model_api_key.get_secret_value() == "project-specific-key"


def test_adaptive_routing_defaults_to_shadow_mode_with_multi_query_disabled() -> None:
    settings = Settings(_env_file=None)

    assert settings.adaptive_routing_enabled is True
    assert settings.adaptive_execution_enabled is False
    assert settings.adaptive_routing_shadow_mode is True
    assert settings.raw_query_enabled is False
    assert settings.raw_query_rollout_percent == 0
    assert settings.multi_query_enabled is False
    assert settings.multi_query_rollout_percent == 0
    assert settings.analyzer_reasoning_effort == "none"
    assert settings.analyzer_max_output_tokens == 1000
    assert settings.analyzer_timeout_seconds == 8.0


def test_adaptive_routing_policy_is_built_from_environment(monkeypatch) -> None:
    monkeypatch.setenv("YAN_AGENT_ADAPTIVE_ROUTING_SHADOW_MODE", "false")
    monkeypatch.setenv("YAN_AGENT_RAW_QUERY_ENABLED", "true")
    monkeypatch.setenv("YAN_AGENT_RAW_QUERY_ROLLOUT_PERCENT", "10")
    monkeypatch.setenv("YAN_AGENT_MULTI_QUERY_ENABLED", "true")
    monkeypatch.setenv("YAN_AGENT_MULTI_QUERY_ROLLOUT_PERCENT", "12")
    monkeypatch.setenv("YAN_AGENT_MULTI_QUERY_QUALITY_GATE_PASSED", "true")
    monkeypatch.setenv("YAN_AGENT_ROUTING_ROLLOUT_SALT", "router-release-7")

    policy = Settings(_env_file=None).adaptive_routing_policy

    assert policy.shadow_mode is False
    assert policy.raw_query_enabled is True
    assert policy.raw_query_rollout_percent == 10
    assert policy.multi_query_enabled is True
    assert policy.multi_query_rollout_percent == 12
    assert policy.rollout_salt == "router-release-7"


def test_raw_query_rollout_requires_the_feature_to_be_enabled(monkeypatch) -> None:
    monkeypatch.setenv("YAN_AGENT_RAW_QUERY_ENABLED", "false")
    monkeypatch.setenv("YAN_AGENT_RAW_QUERY_ROLLOUT_PERCENT", "10")

    with pytest.raises(ValueError, match="raw query rollout requires raw query"):
        Settings(_env_file=None)


def test_multi_query_rollout_requires_a_passed_quality_gate(monkeypatch) -> None:
    monkeypatch.setenv("YAN_AGENT_MULTI_QUERY_ENABLED", "true")
    monkeypatch.setenv("YAN_AGENT_MULTI_QUERY_ROLLOUT_PERCENT", "1")
    monkeypatch.setenv("YAN_AGENT_MULTI_QUERY_QUALITY_GATE_PASSED", "false")

    with pytest.raises(ValueError, match="quality gate"):
        Settings(_env_file=None)


def test_adaptive_execution_requires_the_router(monkeypatch) -> None:
    monkeypatch.setenv("YAN_AGENT_ADAPTIVE_EXECUTION_ENABLED", "true")
    monkeypatch.setenv("YAN_AGENT_ADAPTIVE_ROUTING_ENABLED", "false")

    with pytest.raises(ValueError, match="requires adaptive routing"):
        Settings(_env_file=None)
