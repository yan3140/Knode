from datetime import UTC, datetime, timedelta

import httpx
import jwt
import pytest

from yan_agent.domain.qa import (
    AgentInvocation,
    AgentResult,
    AgentStatus,
    KnowledgeCitation,
    RetrievalMode,
)
from yan_agent.main import create_app
from yan_agent.security.principal_token import PrincipalTokenVerifier

SECRET = "0123456789abcdef0123456789abcdef"


class ReadyProbe:
    async def check(self) -> dict[str, bool]:
        return {"postgres": True, "redis": True}


class RecordingAgent:
    def __init__(self) -> None:
        self.invocations: list[AgentInvocation] = []

    async def invoke(self, invocation: AgentInvocation) -> AgentResult:
        self.invocations.append(invocation)
        return AgentResult(
            run_id=invocation.run_id,
            status=AgentStatus.SUCCEEDED,
            answer="队列满时执行拒绝策略。[1]",
            citations=[
                KnowledgeCitation(
                    index=1,
                    chunk_id="chunk-1",
                    document_id="document-1",
                    document_version_id="version-2",
                    title="线程池",
                    section="拒绝策略",
                    source="article:7",
                    content="队列满时执行配置的拒绝策略。",
                    final_score=0.9,
                )
            ],
            query_ids=[9],
            selected_mode=RetrievalMode.HYBRID,
            retrieval_attempts=1,
        )


def principal_token(knowledge_base_id: int) -> str:
    now = datetime.now(UTC)
    return jwt.encode(
        {
            "aud": ["yan-agent"],
            "exp": now + timedelta(seconds=60),
            "iat": now,
            "iss": "yanblog",
            "jti": "principal-1",
            "knowledgeBaseId": knowledge_base_id,
            "sub": "3",
        },
        SECRET,
        algorithm="HS256",
    )


@pytest.mark.asyncio
async def test_invokes_agent_with_identity_from_principal_token() -> None:
    agent = RecordingAgent()
    app = create_app(
        readiness_probe=ReadyProbe(),
        agent=agent,
        principal_verifier=PrincipalTokenVerifier(SECRET),
    )

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/internal/v1/agent/invocations",
            headers={"X-Principal-Context": principal_token(5)},
            json={
                "runId": "run-1",
                "knowledgeBaseId": 5,
                "question": "线程池队列满了怎么办？",
                "requestedMode": "AUTO",
            },
        )

    assert response.status_code == 200
    assert response.json()["status"] == "SUCCEEDED"
    assert response.json()["citations"][0]["documentVersionId"] == "version-2"
    assert agent.invocations[0].user_id == 3


@pytest.mark.asyncio
async def test_rejects_knowledge_base_outside_principal_scope() -> None:
    agent = RecordingAgent()
    app = create_app(
        readiness_probe=ReadyProbe(),
        agent=agent,
        principal_verifier=PrincipalTokenVerifier(SECRET),
    )

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/internal/v1/agent/invocations",
            headers={"X-Principal-Context": principal_token(7)},
            json={
                "runId": "run-scope",
                "knowledgeBaseId": 5,
                "question": "越权问题",
            },
        )

    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "KNOWLEDGE_BASE_FORBIDDEN"
    assert agent.invocations == []
