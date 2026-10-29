import httpx
import pytest

from yan_agent.adapters.java_knowledge.client import JavaKnowledgeClient, KnowledgeServiceError
from yan_agent.adapters.java_knowledge.contracts import InternalRetrievalRequest, RetrievalMode
from yan_agent.domain.errors import AgentErrorCode


@pytest.mark.asyncio
async def test_retrieves_versioned_citations_with_service_identity() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Agent-Service-Key"] == "service-key"
        assert request.url.path == "/internal/api/knowledge/retrievals"
        request_body = request.content.decode("utf-8")
        assert request_body.find('"knowledgeBaseId":5') >= 0
        assert request_body.find('"topK":5') >= 0
        assert request_body.find('"candidateK":15') >= 0
        return httpx.Response(
            200,
            json={
                "queryId": 9,
                "requestedMode": "AUTO",
                "selectedMode": "HYBRID",
                "citations": [
                    {
                        "chunkId": "chunk-1",
                        "documentId": "document-1",
                        "documentVersionId": "version-2",
                        "title": "线程池",
                        "section": "拒绝策略",
                        "source": "article:7",
                        "content": "队列满时执行配置的拒绝策略。",
                        "vectorSimilarity": None,
                        "keywordScore": 1.5,
                        "rrfScore": 0.5,
                        "finalScore": 0.9,
                        "exactMatch": False,
                        "exactType": None,
                    }
                ],
                "degraded": False,
                "degradationReasons": [],
                "totalElapsedMillis": 12,
                "traceId": "trace-1",
            },
        )

    async with JavaKnowledgeClient(
        base_url="http://yanblog.internal",
        service_key="service-key",
        transport=httpx.MockTransport(handler),
    ) as client:
        result = await client.retrieve(
            InternalRetrievalRequest(
                retrieval_request_id="retrieval-1",
                run_id="run-1",
                user_id=3,
                knowledge_base_id=5,
                query="线程池队列满了怎么办？",
                requested_mode=RetrievalMode.AUTO,
                top_k=5,
                candidate_k=15,
            )
        )

    assert result.query_id == 9
    assert result.citations[0].document_version_id == "version-2"
    assert result.trace_id == "trace-1"


def retrieval_request() -> InternalRetrievalRequest:
    return InternalRetrievalRequest(
        retrieval_request_id="retrieval-error",
        run_id="run-error",
        user_id=3,
        knowledge_base_id=5,
        query="error",
        requested_mode=RetrievalMode.AUTO,
    )


def test_retrieval_request_defaults_to_frozen_experiment_controls() -> None:
    request = retrieval_request()

    assert request.top_k == 10
    assert request.candidate_k == 30


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status_code", "error_code", "retryable"),
    [
        (403, AgentErrorCode.KNOWLEDGE_FORBIDDEN, False),
        (503, AgentErrorCode.KNOWLEDGE_UNAVAILABLE, True),
    ],
)
async def test_classifies_knowledge_service_errors(
    status_code: int,
    error_code: AgentErrorCode,
    retryable: bool,
) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status_code, request=request)

    async with JavaKnowledgeClient(
        base_url="http://yanblog.internal",
        service_key="service-key",
        transport=httpx.MockTransport(handler),
    ) as client:
        with pytest.raises(KnowledgeServiceError) as raised:
            await client.retrieve(retrieval_request())

    assert raised.value.code is error_code
    assert raised.value.retryable is retryable
