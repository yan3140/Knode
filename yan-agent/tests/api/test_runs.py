from datetime import UTC, datetime, timedelta

import httpx
import jwt
import pytest

from yan_agent.adapters.memory.context_cache import InMemoryContextCache
from yan_agent.adapters.memory.run_repository import InMemoryRunRepository
from yan_agent.application.context_manager import ContextManager
from yan_agent.application.run_service import RunService
from yan_agent.domain.qa import AgentResult, AgentStatus
from yan_agent.domain.runs import CreateRunCommand
from yan_agent.main import create_app
from yan_agent.security.principal_token import PrincipalTokenVerifier

SECRET = "0123456789abcdef0123456789abcdef"


class ReadyProbe:
    async def check(self) -> dict[str, bool]:
        return {"postgres": True, "redis": True}


class SequenceIds:
    def __init__(self) -> None:
        self._value = 0

    def __call__(self) -> str:
        self._value += 1
        return f"id-{self._value}"


def principal_token() -> str:
    now = datetime.now(UTC)
    return jwt.encode(
        {
            "aud": ["yan-agent"],
            "exp": now + timedelta(seconds=60),
            "iat": now,
            "iss": "yanblog",
            "jti": "principal-run-1",
            "knowledgeBaseId": 5,
            "sub": "3",
        },
        SECRET,
        algorithm="HS256",
    )


@pytest.mark.asyncio
async def test_async_run_api_is_idempotent_and_queryable() -> None:
    repository = InMemoryRunRepository()
    contexts = ContextManager(repository, InMemoryContextCache())
    service = RunService(repository, SequenceIds(), contexts)
    app = create_app(
        readiness_probe=ReadyProbe(),
        run_service=service,
        principal_verifier=PrincipalTokenVerifier(SECRET),
    )
    transport = httpx.ASGITransport(app=app)
    headers = {"X-Principal-Context": principal_token()}
    payload = {
        "clientRequestId": "client-request-1",
        "conversationId": "conversation-1",
        "knowledgeBaseId": 5,
        "question": "线程池队列满了怎么办？",
        "requestedMode": "AUTO",
    }

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        first = await client.post("/internal/v1/agent/runs", headers=headers, json=payload)
        duplicate = await client.post("/internal/v1/agent/runs", headers=headers, json=payload)
        fetched = await client.get(
            f"/internal/v1/agent/runs/{first.json()['runId']}",
            headers=headers,
        )
        conversation = await client.get(
            "/internal/v1/agent/conversations/conversation-1",
            headers=headers,
        )
        events = await client.get(
            f"/internal/v1/agent/runs/{first.json()['runId']}/events",
            headers=headers,
        )

    assert first.status_code == 202
    assert duplicate.status_code == 202
    assert first.json()["runId"] == duplicate.json()["runId"]
    assert first.json()["created"] is True
    assert duplicate.json()["created"] is False
    assert fetched.status_code == 200
    assert fetched.json()["status"] == "QUEUED"
    assert conversation.status_code == 200
    assert conversation.json()["contextVersion"] == 1
    assert conversation.json()["messages"][0]["content"] == "线程池队列满了怎么办？"
    assert events.status_code == 200
    assert events.json()[0]["eventType"] == "run.queued"


@pytest.mark.asyncio
async def test_run_lookup_rechecks_knowledge_base_scope() -> None:
    repository = InMemoryRunRepository()
    service = RunService(repository, SequenceIds())
    enqueued = await service.enqueue(
        CreateRunCommand(
            client_request_id="client-other-kb",
            conversation_id="conversation-other-kb",
            user_id=3,
            knowledge_base_id=6,
            question="另一个知识库的问题",
        )
    )
    app = create_app(
        readiness_probe=ReadyProbe(),
        run_service=service,
        principal_verifier=PrincipalTokenVerifier(SECRET),
    )
    transport = httpx.ASGITransport(app=app)

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            f"/internal/v1/agent/runs/{enqueued.run.id}",
            headers={"X-Principal-Context": principal_token()},
        )

    assert response.status_code == 403


@pytest.mark.asyncio
async def test_resume_api_requeues_waiting_run_and_persists_user_input() -> None:
    repository = InMemoryRunRepository()
    contexts = ContextManager(repository, InMemoryContextCache())
    service = RunService(repository, SequenceIds(), contexts)
    enqueued = await service.enqueue(
        CreateRunCommand(
            client_request_id="client-resume",
            conversation_id="conversation-resume",
            user_id=3,
            knowledge_base_id=5,
            question="这个怎么配？",
        )
    )
    now = datetime.now(UTC)
    claimed = await repository.claim_next(
        "worker-1",
        now,
        now + timedelta(seconds=30),
    )
    assert claimed is not None
    await repository.mark_waiting(
        claimed.id,
        "worker-1",
        AgentResult(
            run_id=claimed.id,
            status=AgentStatus.CLARIFICATION_REQUIRED,
            answer="你指的是哪个服务？",
            citations=[],
            query_ids=[],
        ),
    )
    app = create_app(
        readiness_probe=ReadyProbe(),
        run_service=service,
        principal_verifier=PrincipalTokenVerifier(SECRET),
    )
    transport = httpx.ASGITransport(app=app)

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            f"/internal/v1/agent/runs/{enqueued.run.id}/resume",
            headers={"X-Principal-Context": principal_token()},
            json={"answer": "线程池服务"},
        )

    context = await service.load_context("conversation-resume", user_id=3)
    assert response.status_code == 200
    assert response.json()["status"] == "QUEUED"
    assert context.context_version == 2
    assert context.messages[-1].content == "线程池服务"


@pytest.mark.asyncio
async def test_sse_replays_persisted_events_after_last_event_id() -> None:
    repository = InMemoryRunRepository()
    service = RunService(repository, SequenceIds())
    enqueued = await service.enqueue(
        CreateRunCommand(
            client_request_id="client-sse-replay",
            conversation_id="conversation-sse-replay",
            user_id=3,
            knowledge_base_id=5,
            question="重放事件",
        )
    )
    now = datetime.now(UTC)
    claimed = await repository.claim_next("worker-sse", now, now + timedelta(seconds=30))
    assert claimed is not None
    await repository.complete(
        claimed.id,
        "worker-sse",
        AgentResult(
            run_id=claimed.id,
            status=AgentStatus.SUCCEEDED,
            answer="完成",
            citations=[],
            query_ids=[7],
        ),
        "message-sse",
        now,
    )
    app = create_app(
        readiness_probe=ReadyProbe(),
        run_service=service,
        principal_verifier=PrincipalTokenVerifier(SECRET),
    )

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
    ) as client:
        response = await client.get(
            f"/internal/v1/agent/runs/{enqueued.run.id}/events",
            headers={
                "X-Principal-Context": principal_token(),
                "Accept": "text/event-stream",
                "Last-Event-ID": "1",
            },
        )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "id: 1\n" not in response.text
    assert "id: 2\nevent: run.started\n" in response.text
    assert "id: 3\nevent: retrieval.completed\n" in response.text
    assert "id: 4\nevent: answer.delta\n" in response.text
    assert "id: 5\nevent: citations.final\n" in response.text
    assert "id: 6\nevent: run.completed\n" in response.text
    assert response.text.index("id: 2") < response.text.index("id: 6")


@pytest.mark.asyncio
async def test_sse_rejects_invalid_last_event_id() -> None:
    repository = InMemoryRunRepository()
    service = RunService(repository, SequenceIds())
    enqueued = await service.enqueue(
        CreateRunCommand(
            client_request_id="client-sse-invalid",
            conversation_id="conversation-sse-invalid",
            user_id=3,
            knowledge_base_id=5,
            question="非法游标",
        )
    )
    app = create_app(
        readiness_probe=ReadyProbe(),
        run_service=service,
        principal_verifier=PrincipalTokenVerifier(SECRET),
    )

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
    ) as client:
        response = await client.get(
            f"/internal/v1/agent/runs/{enqueued.run.id}/events",
            headers={
                "X-Principal-Context": principal_token(),
                "Accept": "text/event-stream",
                "Last-Event-ID": "not-a-sequence",
            },
        )

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "INVALID_EVENT_CURSOR"
