import sys
from datetime import UTC, datetime
from pathlib import Path

import pytest

from yan_agent.operations import cli
from yan_agent.operations.cli import (
    OperationsError,
    compose_command,
    validate_rollback_image,
    validate_secrets,
)
from yan_agent.operations.rollout_gate import RolloutSnapshot


def test_secret_validation_requires_long_service_credentials() -> None:
    with pytest.raises(OperationsError, match="at least 32 bytes"):
        validate_secrets(
            {
                "OPENAI_API_KEY": "model-key",
                "YANBLOG_AGENT_SERVICE_KEY": "short",
                "YANBLOG_AGENT_PRINCIPAL_JWT_SECRET": "x" * 32,
            }
        )


def test_rollback_rejects_mutable_image_and_accepts_digest() -> None:
    with pytest.raises(OperationsError, match="immutable"):
        validate_rollback_image("yan-agent:latest")
    with pytest.raises(OperationsError, match="immutable"):
        validate_rollback_image("registry.example/yan-agent:0.7.0")

    validate_rollback_image("registry.example/yan-agent@sha256:" + "a" * 64)


def test_compose_command_is_explicit_about_env_and_compose_files() -> None:
    command = compose_command(Path("compose.agent.yml"), Path(".env"), "config", "--quiet")

    assert command == [
        "docker",
        "compose",
        "--env-file",
        ".env",
        "-f",
        "compose.agent.yml",
        "config",
        "--quiet",
    ]


def test_stack_verification_uses_configured_non_default_endpoints(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    json_urls: list[str] = []
    http_urls: list[str] = []

    def fake_wait_json(url: str, attempts: int = 30) -> dict[str, object]:
        json_urls.append(url)
        return {"status": "UP"}

    def fake_wait_http(url: str, attempts: int = 30) -> None:
        http_urls.append(url)

    monkeypatch.setattr(cli, "_wait_json", fake_wait_json)
    monkeypatch.setattr(cli, "_wait_http", fake_wait_http)

    cli._verify_stack(
        {
            "YAN_AGENT_OPS_API_URL": "http://127.0.0.1:18990/",
            "YAN_AGENT_OPS_PROMETHEUS_URL": "http://127.0.0.1:19090/",
            "YAN_AGENT_OPS_JAEGER_URL": "http://127.0.0.1:16687/",
            "YAN_AGENT_OPS_COLLECTOR_URL": "http://127.0.0.1:13134/",
        }
    )

    assert json_urls == ["http://127.0.0.1:18990/health/ready"]
    assert http_urls == [
        "http://127.0.0.1:18990/metrics",
        "http://127.0.0.1:19090/-/ready",
        "http://127.0.0.1:16687/",
        "http://127.0.0.1:13134/",
    ]


def test_rollout_check_bypasses_docker_and_writes_a_blocked_report(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    output = tmp_path / "shadow.json"
    snapshot = RolloutSnapshot(
        collected_at=datetime(2026, 8, 22, tzinfo=UTC),
        window_hours=24,
    )
    monkeypatch.setattr(cli, "_preflight", lambda *_args: pytest.fail("Docker was used"))
    monkeypatch.setattr(cli, "collect_rollout_snapshot", lambda *_args, **_kwargs: snapshot)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "yan-agent-ops",
            "--env-file",
            str(tmp_path / "not-required.env"),
            "rollout-check",
            "--stage",
            "shadow",
            "--prometheus-url",
            "http://127.0.0.1:19090",
            "--output",
            str(output),
        ],
    )

    with pytest.raises(SystemExit) as stopped:
        cli.main()

    assert stopped.value.code == 2
    assert output.is_file()
    assert output.with_suffix(".md").is_file()
