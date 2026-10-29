import time
from types import TracebackType

import httpx
from opentelemetry import propagate, trace
from opentelemetry.trace import Status, StatusCode
from pydantic import ValidationError

from yan_agent.adapters.java_knowledge.contracts import (
    InternalRetrievalRequest,
    InternalRetrievalResponse,
)
from yan_agent.domain.errors import (
    AgentErrorCode,
    AgentExecutionError,
)
from yan_agent.observability.metrics import AgentMetrics

tracer = trace.get_tracer(__name__)


class KnowledgeServiceError(AgentExecutionError):
    def __init__(self, status_code: int, message: str) -> None:
        forbidden = status_code in {401, 403}
        super().__init__(
            (
                AgentErrorCode.KNOWLEDGE_FORBIDDEN
                if forbidden
                else AgentErrorCode.KNOWLEDGE_UNAVAILABLE
            ),
            message,
            retryable=(status_code in {408, 429} or status_code >= 500),
        )
        self.status_code = status_code


class JavaKnowledgeClient:
    def __init__(
        self,
        base_url: str,
        service_key: str,
        transport: httpx.AsyncBaseTransport | None = None,
        metrics: AgentMetrics | None = None,
    ) -> None:
        if not service_key:
            raise ValueError("Java knowledge service key must not be blank")
        self._service_key = service_key
        self._metrics = metrics
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=httpx.Timeout(10, connect=2),
            transport=transport,
        )

    async def __aenter__(self) -> "JavaKnowledgeClient":
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        await self._client.aclose()

    async def retrieve(self, request: InternalRetrievalRequest) -> InternalRetrievalResponse:
        started_at = time.perf_counter()
        outcome = "error"
        with tracer.start_as_current_span("knowledge.retrieve") as span:
            span.set_attribute("agent.run.id", request.run_id)
            span.set_attribute("agent.retrieval.request_id", request.retrieval_request_id)
            span.set_attribute("agent.retrieval.mode", request.requested_mode.value)
            headers = {"X-Agent-Service-Key": self._service_key}
            propagate.inject(headers)
            try:
                response = await self._client.post(
                    "/internal/api/knowledge/retrievals",
                    headers=headers,
                    json=request.model_dump(by_alias=True, mode="json"),
                )
                span.set_attribute("http.response.status_code", response.status_code)
                if response.status_code != 200:
                    raise KnowledgeServiceError(
                        response.status_code,
                        "Java knowledge retrieval failed",
                    )
                parsed = InternalRetrievalResponse.model_validate(response.json())
            except httpx.HTTPError as error:
                span.set_status(Status(StatusCode.ERROR, type(error).__name__))
                raise KnowledgeServiceError(
                    503,
                    "Java knowledge retrieval unavailable",
                ) from error
            except (ValueError, ValidationError) as error:
                span.set_status(Status(StatusCode.ERROR, type(error).__name__))
                raise KnowledgeServiceError(502, "Java knowledge response is invalid") from error
            except KnowledgeServiceError as error:
                span.set_status(Status(StatusCode.ERROR, error.code.value))
                raise
            else:
                outcome = "success"
                span.set_attribute("agent.query.id", parsed.query_id)
                span.set_attribute("agent.retrieval.degraded", parsed.degraded)
                return parsed
            finally:
                if self._metrics is not None:
                    self._metrics.observe_dependency(
                        "java_knowledge",
                        "retrieve",
                        outcome,
                        time.perf_counter() - started_at,
                    )
