import argparse
import json
import os
import secrets
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx
import jwt
from dotenv import dotenv_values
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from pydantic.alias_generators import to_camel

from yan_agent.ids import new_ulid

_STOP_EVENTS = {"run.completed", "run.failed", "run.cancelled", "run.waiting_input"}


class DemoError(RuntimeError):
    pass


class DemoScenario(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    name: str = Field(min_length=1, max_length=100)
    user_id: int = Field(gt=0)
    knowledge_base_id: int = Field(gt=0)
    question: str = Field(min_length=1, max_length=2000)
    requested_mode: str = "AUTO"
    clarification_answer: str | None = Field(default=None, min_length=1, max_length=2000)
    expected_status: str | None = None


class DemoSuite(BaseModel):
    scenarios: list[DemoScenario] = Field(min_length=1)


class SseEvent(BaseModel):
    sequence: int = Field(gt=0)
    event_type: str


def load_suite(path: Path) -> DemoSuite:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        return DemoSuite.model_validate(payload)
    except (OSError, ValueError, ValidationError) as error:
        raise DemoError(f"Invalid demo scenario file: {path}") from error


def load_secret(env_file: Path) -> str:
    values = {key: value for key, value in dotenv_values(env_file).items() if value is not None}
    values.update(os.environ)
    secret = values.get("YANBLOG_AGENT_PRINCIPAL_JWT_SECRET") or values.get(
        "YAN_AGENT_PRINCIPAL_JWT_SECRET",
        "",
    )
    if len(secret.encode("utf-8")) < 32:
        raise DemoError("Principal JWT secret must be configured with at least 32 bytes")
    return secret


def issue_principal_token(secret: str, scenario: DemoScenario) -> str:
    now = datetime.now(UTC)
    return jwt.encode(
        {
            "aud": ["yan-agent"],
            "exp": now + timedelta(minutes=5),
            "iat": now,
            "iss": "yanblog",
            "jti": new_ulid(),
            "knowledgeBaseId": scenario.knowledge_base_id,
            "sub": str(scenario.user_id),
        },
        secret,
        algorithm="HS256",
    )


def run_scenario(
    client: httpx.Client,
    scenario: DemoScenario,
    secret: str,
) -> dict[str, object]:
    token = issue_principal_token(secret, scenario)
    trace_id = secrets.token_hex(16)
    conversation_id = new_ulid()
    headers = {"X-Principal-Context": token}
    create_response = client.post(
        "/internal/v1/agent/runs",
        headers={**headers, "traceparent": _traceparent(trace_id)},
        json={
            "clientRequestId": new_ulid(),
            "conversationId": conversation_id,
            "knowledgeBaseId": scenario.knowledge_base_id,
            "question": scenario.question,
            "requestedMode": scenario.requested_mode,
        },
    )
    create_response.raise_for_status()
    created = _json_object(create_response)
    run_id = _string_value(created, "runId")

    events = _read_events(
        client,
        run_id,
        headers,
        trace_id,
        after_sequence=0,
        max_events=1,
    )
    if not events:
        raise DemoError("The first SSE connection returned no durable event")
    disconnected_after = events[-1].sequence
    resumed = False

    while events[-1].event_type not in _STOP_EVENTS:
        events.extend(
            _read_events(
                client,
                run_id,
                headers,
                trace_id,
                after_sequence=events[-1].sequence,
            )
        )

    if events[-1].event_type == "run.waiting_input" and scenario.clarification_answer:
        resume_response = client.post(
            f"/internal/v1/agent/runs/{run_id}/resume",
            headers={**headers, "traceparent": _traceparent(trace_id)},
            json={"answer": scenario.clarification_answer},
        )
        resume_response.raise_for_status()
        resumed = True
        events.extend(
            _read_events(
                client,
                run_id,
                headers,
                trace_id,
                after_sequence=events[-1].sequence,
            )
        )

    final_response = client.get(
        f"/internal/v1/agent/runs/{run_id}",
        headers={**headers, "traceparent": _traceparent(trace_id)},
    )
    final_response.raise_for_status()
    final_run = _json_object(final_response)
    status = _string_value(final_run, "status")
    if scenario.expected_status is not None and status != scenario.expected_status:
        raise DemoError(
            f"Scenario {scenario.name!r} expected {scenario.expected_status}, received {status}"
        )

    return {
        "scenario": scenario.name,
        "runId": run_id,
        "conversationId": conversation_id,
        "traceId": trace_id,
        "status": status,
        "attemptCount": final_run.get("attemptCount"),
        "queryIds": final_run.get("queryIds", []),
        "eventTypes": [event.event_type for event in events],
        "sseDisconnectedAfter": disconnected_after,
        "sseResumedFrom": disconnected_after,
        "clarificationResumed": resumed,
    }


def _read_events(
    client: httpx.Client,
    run_id: str,
    headers: dict[str, str],
    trace_id: str,
    *,
    after_sequence: int,
    max_events: int | None = None,
) -> list[SseEvent]:
    request_headers = {
        **headers,
        "Accept": "text/event-stream",
        "Last-Event-ID": str(after_sequence),
        "traceparent": _traceparent(trace_id),
    }
    collected: list[SseEvent] = []
    with client.stream(
        "GET",
        f"/internal/v1/agent/runs/{run_id}/events",
        headers=request_headers,
    ) as response:
        response.raise_for_status()
        event_id: str | None = None
        event_type: str | None = None
        for line in response.iter_lines():
            if line.startswith("id:"):
                event_id = line.removeprefix("id:").strip()
            elif line.startswith("event:"):
                event_type = line.removeprefix("event:").strip()
            elif line == "" and event_id is not None and event_type is not None:
                try:
                    event = SseEvent(sequence=int(event_id), event_type=event_type)
                except (ValueError, ValidationError) as error:
                    raise DemoError("Agent returned an invalid SSE event") from error
                collected.append(event)
                event_id = None
                event_type = None
                if event.event_type in _STOP_EVENTS or (
                    max_events is not None and len(collected) >= max_events
                ):
                    return collected
    if not collected:
        raise DemoError("Agent SSE stream ended before a durable event was received")
    return collected


def _traceparent(trace_id: str) -> str:
    return f"00-{trace_id}-{secrets.token_hex(8)}-01"


def _json_object(response: httpx.Response) -> dict[str, Any]:
    payload = response.json()
    if not isinstance(payload, dict):
        raise DemoError("Agent returned a non-object JSON response")
    return payload


def _string_value(payload: dict[str, Any], name: str) -> str:
    value = payload.get(name)
    if not isinstance(value, str) or not value:
        raise DemoError(f"Agent response is missing {name}")
    return value


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run a sanitized Yan Agent interview demo")
    parser.add_argument("--scenarios", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--base-url", default="http://127.0.0.1:8990")
    parser.add_argument("--timeout-seconds", type=float, default=120)
    return parser


def main() -> None:
    arguments = _parser().parse_args()
    try:
        suite = load_suite(arguments.scenarios)
        secret = load_secret(arguments.env_file)
        if arguments.timeout_seconds <= 0:
            raise DemoError("--timeout-seconds must be positive")
        with httpx.Client(
            base_url=arguments.base_url,
            timeout=httpx.Timeout(arguments.timeout_seconds),
        ) as client:
            scenarios = [run_scenario(client, scenario, secret) for scenario in suite.scenarios]
        report = {
            "schemaVersion": "1.0",
            "generatedAt": datetime.now(UTC).isoformat(),
            "baseUrl": arguments.base_url,
            "scenarios": scenarios,
        }
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"Demo passed: {len(scenarios)} scenario(s); report={arguments.output}")
    except (DemoError, httpx.HTTPError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
