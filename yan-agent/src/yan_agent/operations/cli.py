import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Mapping, Sequence
from pathlib import Path

from dotenv import dotenv_values
from pydantic import ValidationError

from yan_agent.operations.rollout_gate import (
    ManualReviewEvidence,
    PrometheusClient,
    RolloutCollectionError,
    RolloutGateStatus,
    RolloutSnapshot,
    RolloutStage,
    collect_rollout_snapshot,
    evaluate_rollout,
    write_rollout_report,
)

_REQUIRED_SECRETS = (
    "OPENAI_API_KEY",
    "YANBLOG_AGENT_SERVICE_KEY",
    "YANBLOG_AGENT_PRINCIPAL_JWT_SECRET",
)


class OperationsError(RuntimeError):
    pass


def load_environment(env_file: Path) -> dict[str, str]:
    values = {key: value for key, value in dotenv_values(env_file).items() if value is not None}
    values.update(os.environ)
    return values


def validate_secrets(environment: Mapping[str, str]) -> None:
    missing = [name for name in _REQUIRED_SECRETS if not environment.get(name, "").strip()]
    if missing:
        raise OperationsError(f"Missing required environment variables: {', '.join(missing)}")
    for name in (
        "YANBLOG_AGENT_SERVICE_KEY",
        "YANBLOG_AGENT_PRINCIPAL_JWT_SECRET",
    ):
        if len(environment[name].encode("utf-8")) < 32:
            raise OperationsError(f"{name} must be at least 32 bytes")


def validate_rollback_image(image: str) -> None:
    normalized = image.strip().lower()
    digest = normalized.rpartition("@sha256:")[2]
    if len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest):
        raise OperationsError("Rollback requires an immutable sha256 image digest")


def compose_command(compose_file: Path, env_file: Path, *arguments: str) -> list[str]:
    return [
        "docker",
        "compose",
        "--env-file",
        str(env_file),
        "-f",
        str(compose_file),
        *arguments,
    ]


def _run(command: Sequence[str], environment: Mapping[str, str] | None = None) -> None:
    subprocess.run(command, env=None if environment is None else dict(environment), check=True)


def _preflight(compose_file: Path, env_file: Path) -> dict[str, str]:
    if shutil.which("docker") is None:
        raise OperationsError("Docker CLI is not installed or is not on PATH")
    if not compose_file.is_file():
        raise OperationsError(f"Compose file does not exist: {compose_file}")
    if not env_file.is_file():
        raise OperationsError(f"Environment file does not exist: {env_file}")
    environment = load_environment(env_file)
    validate_secrets(environment)
    _run(compose_command(compose_file, env_file, "config", "--quiet"), environment)
    return environment


def _wait_json(url: str, attempts: int = 30) -> dict[str, object]:
    last_error: Exception | None = None
    for _attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                payload = json.load(response)
            if not isinstance(payload, dict):
                raise OperationsError(f"Expected JSON object from {url}")
            return payload
        except (OSError, ValueError, urllib.error.URLError) as error:
            last_error = error
            time.sleep(1)
    raise OperationsError(f"Endpoint did not become ready: {url}") from last_error


def _wait_http(url: str, attempts: int = 30) -> None:
    last_error: Exception | None = None
    for _attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                if 200 <= response.status < 400:
                    return
        except (OSError, urllib.error.URLError) as error:
            last_error = error
        time.sleep(1)
    raise OperationsError(f"Endpoint did not become ready: {url}") from last_error


def _verify_stack(environment: Mapping[str, str]) -> None:
    api_url = environment.get("YAN_AGENT_OPS_API_URL", "http://127.0.0.1:8990").rstrip("/")
    prometheus_url = environment.get(
        "YAN_AGENT_OPS_PROMETHEUS_URL", "http://127.0.0.1:9090"
    ).rstrip("/")
    jaeger_url = environment.get("YAN_AGENT_OPS_JAEGER_URL", "http://127.0.0.1:16686").rstrip("/")
    collector_url = environment.get("YAN_AGENT_OPS_COLLECTOR_URL", "http://127.0.0.1:13133").rstrip(
        "/"
    )
    ready = _wait_json(f"{api_url}/health/ready")
    if ready.get("status") != "UP":
        raise OperationsError(f"Agent readiness is not UP: {ready.get('status')}")
    _wait_http(f"{api_url}/metrics")
    _wait_http(f"{prometheus_url}/-/ready")
    _wait_http(f"{jaeger_url}/")
    _wait_http(f"{collector_url}/")


def _backup(
    compose_file: Path,
    env_file: Path,
    environment: Mapping[str, str],
    output: Path,
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    command = compose_command(
        compose_file,
        env_file,
        "exec",
        "-T",
        "agent-postgres",
        "pg_dump",
        "-U",
        "yan_agent",
        "-d",
        "yan_agent",
        "-Fc",
    )
    with output.open("wb") as stream:
        result = subprocess.run(command, env=dict(environment), stdout=stream, check=False)
    if result.returncode != 0:
        output.unlink(missing_ok=True)
        raise OperationsError("PostgreSQL backup failed")


def _fault_drill(
    compose_file: Path,
    env_file: Path,
    environment: Mapping[str, str],
    target: str,
    hold_seconds: float,
) -> None:
    service = {
        "redis": "agent-redis",
        "worker": "yan-agent-worker",
        "api": "yan-agent",
        "collector": "agent-otel-collector",
    }[target]
    _run(compose_command(compose_file, env_file, "stop", service), environment)
    try:
        time.sleep(hold_seconds)
        api_url = environment.get("YAN_AGENT_OPS_API_URL", "http://127.0.0.1:8990").rstrip("/")
        if target == "redis":
            ready = _wait_json(f"{api_url}/health/ready", attempts=10)
            if ready.get("status") != "DEGRADED":
                raise OperationsError("Redis drill expected API readiness to be DEGRADED")
        elif target == "collector":
            ready = _wait_json(f"{api_url}/health/ready", attempts=10)
            if ready.get("status") != "UP":
                raise OperationsError("Collector outage must not make the API unavailable")
    finally:
        _run(compose_command(compose_file, env_file, "start", service), environment)
    if target in {"redis", "api"}:
        _wait_json(f"{api_url}/health/ready")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Operate and validate Yan Agent releases")
    parser.add_argument("--compose-file", type=Path, default=Path("compose.agent.yml"))
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("preflight")
    subparsers.add_parser("migrate")
    up = subparsers.add_parser("up")
    up.add_argument("--build", action="store_true")
    subparsers.add_parser("verify")
    subparsers.add_parser("status")
    down = subparsers.add_parser("down")
    down.add_argument("--volumes", action="store_true")
    rollback = subparsers.add_parser("rollback")
    rollback.add_argument("--image", required=True)
    backup = subparsers.add_parser("backup")
    backup.add_argument("--output", type=Path, required=True)
    fault = subparsers.add_parser("fault")
    fault.add_argument("target", choices=("redis", "worker", "api", "collector"))
    fault.add_argument("--hold-seconds", type=float, default=5)
    rollout = subparsers.add_parser("rollout-check")
    rollout.add_argument("--stage", choices=tuple(RolloutStage), required=True)
    rollout.add_argument("--prometheus-url")
    rollout.add_argument("--scrape-interval-seconds", type=float, default=10)
    rollout.add_argument("--manual-review", type=Path)
    rollout.add_argument("--output", type=Path, required=True)
    return parser


def _load_manual_review(path: Path | None) -> ManualReviewEvidence | None:
    if path is None:
        return None
    if not path.is_file():
        raise OperationsError(f"Manual review file does not exist: {path}")
    try:
        return ManualReviewEvidence.model_validate_json(path.read_text(encoding="utf-8"))
    except (OSError, ValidationError) as error:
        raise OperationsError(f"Manual review file is invalid: {path}") from error


def _check_rollout(arguments: argparse.Namespace) -> RolloutGateStatus:
    if arguments.scrape_interval_seconds <= 0:
        raise OperationsError("--scrape-interval-seconds must be positive")
    environment = dict(os.environ)
    if arguments.env_file.is_file():
        environment = load_environment(arguments.env_file)
    prometheus_url = (
        arguments.prometheus_url
        or environment.get("YAN_AGENT_OPS_PROMETHEUS_URL")
        or "http://127.0.0.1:9090"
    )
    stage = RolloutStage(arguments.stage)
    window_hours = 48 if stage is RolloutStage.A100 else 24
    client = PrometheusClient(prometheus_url)
    try:
        snapshot = collect_rollout_snapshot(
            client.query,
            window_hours=window_hours,
            scrape_interval_seconds=arguments.scrape_interval_seconds,
        )
    except RolloutCollectionError as error:
        snapshot = RolloutSnapshot(
            window_hours=window_hours,
            collection_errors=[str(error)],
        )
    report = evaluate_rollout(stage, snapshot, _load_manual_review(arguments.manual_review))
    markdown = write_rollout_report(report, arguments.output)
    print(f"Rollout gate {report.status.value}: {arguments.output} (human report: {markdown})")
    return report.status


def main() -> None:
    arguments = _parser().parse_args()
    try:
        if arguments.command == "rollout-check":
            status = _check_rollout(arguments)
            if status is RolloutGateStatus.BLOCKED:
                raise SystemExit(2)
            return
        environment = _preflight(arguments.compose_file, arguments.env_file)
        base = (arguments.compose_file, arguments.env_file)
        if arguments.command == "preflight":
            print("Preflight passed")
        elif arguments.command == "migrate":
            _run(compose_command(*base, "run", "--rm", "yan-agent-migrate"), environment)
        elif arguments.command == "up":
            build = ("--build",) if arguments.build else ()
            _run(compose_command(*base, "up", "-d", *build), environment)
            _verify_stack(environment)
        elif arguments.command == "verify":
            _verify_stack(environment)
            print("Stack verification passed")
        elif arguments.command == "status":
            _run(compose_command(*base, "ps"), environment)
        elif arguments.command == "down":
            suffix = ("--volumes",) if arguments.volumes else ()
            _run(compose_command(*base, "down", *suffix), environment)
        elif arguments.command == "rollback":
            validate_rollback_image(arguments.image)
            rollback_environment = {**environment, "YAN_AGENT_IMAGE": arguments.image}
            _run(
                compose_command(
                    *base,
                    "up",
                    "-d",
                    "--no-deps",
                    "yan-agent",
                    "yan-agent-worker",
                ),
                rollback_environment,
            )
            _verify_stack(rollback_environment)
        elif arguments.command == "backup":
            _backup(*base, environment, arguments.output)
        elif arguments.command == "fault":
            if arguments.hold_seconds < 0:
                raise OperationsError("--hold-seconds must not be negative")
            _fault_drill(*base, environment, arguments.target, arguments.hold_seconds)
    except (OperationsError, RolloutCollectionError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
