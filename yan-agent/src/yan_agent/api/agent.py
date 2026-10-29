from typing import Protocol

from fastapi import APIRouter, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from yan_agent.adapters.java_knowledge.client import KnowledgeServiceError
from yan_agent.adapters.model.wenwen_responses import ModelServiceError
from yan_agent.domain.qa import (
    AgentInvocation,
    AgentResult,
    AgentStatus,
    KnowledgeCitation,
    RetrievalMode,
)
from yan_agent.security.principal_token import InvalidPrincipalToken, PrincipalTokenVerifier


class AgentInvoker(Protocol):
    async def invoke(self, invocation: AgentInvocation) -> AgentResult: ...


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class AgentInvocationRequest(ApiModel):
    run_id: str = Field(min_length=1, max_length=64)
    knowledge_base_id: int = Field(gt=0)
    question: str = Field(min_length=1, max_length=2000)
    requested_mode: RetrievalMode = RetrievalMode.AUTO


class CitationResponse(ApiModel):
    index: int
    chunk_id: str
    document_id: str
    document_version_id: str
    title: str
    section: str
    source: str
    content: str
    final_score: float | None

    @classmethod
    def from_domain(cls, citation: KnowledgeCitation) -> "CitationResponse":
        return cls.model_validate(citation.model_dump())


class AgentInvocationResponse(ApiModel):
    run_id: str
    status: AgentStatus
    answer: str
    citations: list[CitationResponse]
    query_ids: list[int]
    selected_mode: RetrievalMode | None
    degraded: bool
    degradation_reasons: list[str]
    retrieval_attempts: int

    @classmethod
    def from_domain(cls, result: AgentResult) -> "AgentInvocationResponse":
        return cls(
            run_id=result.run_id,
            status=result.status,
            answer=result.answer,
            citations=[CitationResponse.from_domain(item) for item in result.citations],
            query_ids=result.query_ids,
            selected_mode=result.selected_mode,
            degraded=result.degraded,
            degradation_reasons=result.degradation_reasons,
            retrieval_attempts=result.retrieval_attempts,
        )


def create_agent_router(
    agent: AgentInvoker | None,
    principal_verifier: PrincipalTokenVerifier | None,
) -> APIRouter:
    router = APIRouter(prefix="/internal/v1/agent", tags=["agent"])

    @router.post("/invocations", response_model=AgentInvocationResponse)
    async def invoke_agent(
        request: AgentInvocationRequest,
        principal_context: str | None = Header(default=None, alias="X-Principal-Context"),
    ) -> AgentInvocationResponse:
        if agent is None or principal_verifier is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail={"code": "AGENT_NOT_CONFIGURED", "message": "Agent is not configured"},
            )
        if principal_context is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"code": "PRINCIPAL_REQUIRED", "message": "Principal token is required"},
            )
        try:
            principal = principal_verifier.verify(principal_context)
        except InvalidPrincipalToken as error:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"code": "INVALID_PRINCIPAL", "message": "Principal token is invalid"},
            ) from error
        if principal.knowledge_base_id != request.knowledge_base_id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={
                    "code": "KNOWLEDGE_BASE_FORBIDDEN",
                    "message": "Knowledge base is outside principal scope",
                },
            )
        try:
            result = await agent.invoke(
                AgentInvocation(
                    run_id=request.run_id,
                    user_id=principal.user_id,
                    knowledge_base_id=request.knowledge_base_id,
                    question=request.question,
                    requested_mode=request.requested_mode,
                )
            )
        except (KnowledgeServiceError, ModelServiceError) as error:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail={"code": "DEPENDENCY_FAILURE", "message": "Agent dependency failed"},
            ) from error
        return AgentInvocationResponse.from_domain(result)

    return router
