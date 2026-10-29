import json
from pathlib import Path

import httpx

from yan_agent.demo.cli import DemoScenario, load_suite, run_scenario

SECRET = "0123456789abcdef0123456789abcdef"


def test_load_suite_accepts_camel_case_fields(tmp_path: Path) -> None:
    path = tmp_path / "scenarios.json"
    path.write_text(
        json.dumps(
            {
                "scenarios": [
                    {
                        "name": "answer",
                        "userId": 3,
                        "knowledgeBaseId": 5,
                        "question": "如何配置？",
                        "expectedStatus": "SUCCEEDED",
                    }
                ]
            }
        ),
        encoding="utf-8",
    )

    suite = load_suite(path)

    assert suite.scenarios[0].knowledge_base_id == 5


def test_run_scenario_disconnects_and_resumes_sse_without_leaking_question() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.method == "POST" and request.url.path.endswith("/runs"):
            return httpx.Response(
                202,
                json={
                    "runId": "run-1",
                    "status": "QUEUED",
                    "attemptCount": 0,
                    "queryIds": [],
                },
            )
        if request.method == "GET" and request.url.path.endswith("/events"):
            cursor = request.headers["Last-Event-ID"]
            if cursor == "0":
                body = 'id: 1\nevent: run.queued\ndata: {"sequence":1}\n\n'
            else:
                body = (
                    'id: 2\nevent: run.started\ndata: {"sequence":2}\n\n'
                    'id: 3\nevent: run.completed\ndata: {"sequence":3}\n\n'
                )
            return httpx.Response(200, text=body, headers={"content-type": "text/event-stream"})
        if request.method == "GET" and request.url.path.endswith("/run-1"):
            return httpx.Response(
                200,
                json={
                    "runId": "run-1",
                    "status": "SUCCEEDED",
                    "attemptCount": 1,
                    "queryIds": [101],
                },
            )
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    scenario = DemoScenario(
        name="answer",
        user_id=3,
        knowledge_base_id=5,
        question="sensitive question",
        expected_status="SUCCEEDED",
    )
    with httpx.Client(
        base_url="http://agent.test",
        transport=httpx.MockTransport(handler),
    ) as client:
        report = run_scenario(client, scenario, SECRET)

    assert report["status"] == "SUCCEEDED"
    assert report["sseDisconnectedAfter"] == 1
    assert report["eventTypes"] == ["run.queued", "run.started", "run.completed"]
    assert "sensitive question" not in json.dumps(report)
    event_requests = [request for request in requests if request.url.path.endswith("/events")]
    assert [request.headers["Last-Event-ID"] for request in event_requests] == ["0", "1"]
    assert len({request.headers["traceparent"].split("-")[1] for request in requests}) == 1
