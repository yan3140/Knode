import json
from collections.abc import AsyncIterator
from typing import Protocol

from fastapi import APIRouter, Header, HTTPException, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel
from sqlalchemy.exc import SQLAlchemyError

from yan_agent.domain.qa import RetrievalMode
from yan_agent.domain.runs import (
    ConversationAccessDenied,
    ConversationContext,
    CreateRunCommand,
    EnqueuedRun,
    IdempotencyConflict,
    RunEvent,
    RunNotFound,
    RunRecord,
)
from yan_agent.observability.tracing import current_trace_headers
from yan_agent.security.principal_token import (
    AgentPrincipal,
    InvalidPrincipalToken,
    PrincipalTokenVerifier,
)


class RunApplication(Protocol):
    async def enqueue(self, command: CreateRunCommand) -> EnqueuedRun: ...

    async def get_run(self, run_id: str, user_id: int) -> RunRecord: ...

    async def resume(self, run_id: str, user_id: int, answer: str) -> RunRecord: ...

    async def list_events(
        self,
        run_id: str,
        user_id: int,
        after_sequence: int = 0,
    ) -> list[RunEvent]: ...

    def watch_events(
        self,
        run_id: str,
        user_id: int,
        after_sequence: int = 0,
    ) -> AsyncIterator[RunEvent | None]: ...

    async def load_context(self, conversation_id: str, user_id: int) -> ConversationContext: ...


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class CreateRunRequest(ApiModel):
    client_request_id: str = Field(min_length=1, max_length=64)
    conversation_id: str = Field(min_length=1, max_length=26)
    knowledge_base_id: int = Field(gt=0)
    question: str = Field(min_length=1, max_length=2000)
    requested_mode: RetrievalMode = RetrievalMode.AUTO


class ResumeRunRequest(ApiModel):
    answer: str = Field(min_length=1, max_length=2000)


class RunResponse(ApiModel):
    run_id: str
    conversation_id: str
    status: str
    created: bool | None = None
    current_node: str | None
    query_ids: list[int]
    attempt_count: int
    result: dict[str, object]
    events_url: str

    @classmethod
    def from_record(cls, run: RunRecord, created: bool | None = None) -> "RunResponse":
        return cls(
            run_id=run.id,
            conversation_id=run.conversation_id,
            status=run.status.value,
            created=created,
            current_node=run.current_node,
            query_ids=run.query_ids,
            attempt_count=run.attempt_count,
            result=run.result,
            events_url=f"/internal/v1/agent/runs/{run.id}/events",
        )


class RunEventResponse(ApiModel):
    sequence: int
    event_type: str
    payload: dict[str, object]
    created_at: str

    @classmethod
    def from_domain(cls, event: RunEvent) -> "RunEventResponse":
        return cls(
            sequence=event.sequence,
            event_type=event.event_type,
            payload=event.payload,
            created_at=event.created_at.isoformat(),
        )


class ConversationMessageResponse(ApiModel):
    message_id: str
    run_id: str | None
    role: str
    content: str
    citations: list[dict[str, object]]
    created_at: str


class ConversationResponse(ApiModel):
    conversation_id: str
    knowledge_base_id: int
    summary: str | None
    context_version: int
    messages: list[ConversationMessageResponse]

    @classmethod
    def from_domain(cls, context: ConversationContext) -> "ConversationResponse":
        return cls(
            conversation_id=context.conversation_id,
            knowledge_base_id=context.knowledge_base_id,
            summary=context.summary,
            context_version=context.context_version,
            messages=[
                ConversationMessageResponse(
                    message_id=message.id,
                    run_id=message.run_id,
                    role=message.role.value,
                    content=message.content,
                    citations=message.citations,
                    created_at=message.created_at.isoformat(),
                )
                for message in context.messages
            ],
        )


def create_runs_router(
    service: RunApplication | None,
    principal_verifier: PrincipalTokenVerifier | None,
) -> APIRouter:
    router = APIRouter(prefix="/internal/v1/agent", tags=["agent-runs"])

    @router.post("/runs", response_model=RunResponse, status_code=status.HTTP_202_ACCEPTED)
    async def create_run(
        request: CreateRunRequest,
        principal_context: str | None = Header(default=None, alias="X-Principal-Context"),
    ) -> RunResponse:
        application, principal = _authorize(service, principal_verifier, principal_context)
        if principal.knowledge_base_id != request.knowledge_base_id:
            raise _forbidden()
        traceparent, tracestate = current_trace_headers()
        try:
            outcome = await application.enqueue(
                CreateRunCommand(
                    client_request_id=request.client_request_id,
                    conversation_id=request.conversation_id,
                    user_id=principal.user_id,
                    knowledge_base_id=request.knowledge_base_id,
                    question=request.question,
                    requested_mode=request.requested_mode,
                    traceparent=traceparent,
                    tracestate=tracestate,
                )
            )
        except ConversationAccessDenied as error:
            raise _forbidden() from error
        except IdempotencyConflict as error:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={"code": "IDEMPOTENCY_CONFLICT", "message": str(error)},
            ) from error
        except SQLAlchemyError as error:
            raise _persistence_unavailable() from error
        return RunResponse.from_record(outcome.run, created=outcome.created)

    @router.get("/runs/{run_id}", response_model=RunResponse)
    async def get_run(
        run_id: str,
        principal_context: str | None = Header(default=None, alias="X-Principal-Context"),
    ) -> RunResponse:
        application, principal = _authorize(service, principal_verifier, principal_context)
        try:
            run = await application.get_run(run_id, principal.user_id)
        except RunNotFound as error:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": "RUN_NOT_FOUND", "message": "Run not found"},
            ) from error
        except SQLAlchemyError as error:
            raise _persistence_unavailable() from error
        if run.knowledge_base_id != principal.knowledge_base_id:
            raise _forbidden()
        return RunResponse.from_record(run)

    @router.get(
        "/runs/{run_id}/events",
        response_model=list[RunEventResponse],
        responses={
            200: {
                "content": {
                    "text/event-stream": {
                        "schema": {"type": "string"},
                    }
                }
            }
        },
    )
    async def get_run_events(
        run_id: str,
        principal_context: str | None = Header(default=None, alias="X-Principal-Context"),
        accept: str | None = Header(default=None, alias="Accept"),
        last_event_id: str | None = Header(default=None, alias="Last-Event-ID"),
    ) -> list[RunEventResponse] | StreamingResponse:
        application, principal = _authorize(service, principal_verifier, principal_context)
        try:
            run = await application.get_run(run_id, principal.user_id)
            if run.knowledge_base_id != principal.knowledge_base_id:
                raise _forbidden()
            if accept is not None and "text/event-stream" in accept.lower():
                after_sequence = _parse_event_cursor(last_event_id)
                return StreamingResponse(
                    _encode_sse(
                        application.watch_events(
                            run_id,
                            principal.user_id,
                            after_sequence,
                        )
                    ),
                    media_type="text/event-stream",
                    headers={
                        "Cache-Control": "no-cache",
                        "X-Accel-Buffering": "no",
                    },
                )
            events = await application.list_events(run_id, principal.user_id)
        except RunNotFound as error:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": "RUN_NOT_FOUND", "message": "Run not found"},
            ) from error
        except SQLAlchemyError as error:
            raise _persistence_unavailable() from error
        return [RunEventResponse.from_domain(event) for event in events]

    @router.post("/runs/{run_id}/resume", response_model=RunResponse)
    async def resume_run(
        run_id: str,
        request: ResumeRunRequest,
        principal_context: str | None = Header(default=None, alias="X-Principal-Context"),
    ) -> RunResponse:
        application, principal = _authorize(service, principal_verifier, principal_context)
        try:
            existing = await application.get_run(run_id, principal.user_id)
            if existing.knowledge_base_id != principal.knowledge_base_id:
                raise _forbidden()
            run = await application.resume(run_id, principal.user_id, request.answer)
        except RunNotFound as error:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": "RUN_NOT_FOUND", "message": "Run not found"},
            ) from error
        except IdempotencyConflict as error:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={"code": "RUN_NOT_WAITING", "message": str(error)},
            ) from error
        except SQLAlchemyError as error:
            raise _persistence_unavailable() from error
        return RunResponse.from_record(run)

    @router.get("/conversations/{conversation_id}", response_model=ConversationResponse)
    async def get_conversation(
        conversation_id: str,
        principal_context: str | None = Header(default=None, alias="X-Principal-Context"),
    ) -> ConversationResponse:
        application, principal = _authorize(service, principal_verifier, principal_context)
        try:
            context = await application.load_context(conversation_id, principal.user_id)
        except (RunNotFound, ConversationAccessDenied) as error:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": "CONVERSATION_NOT_FOUND", "message": "Conversation not found"},
            ) from error
        except SQLAlchemyError as error:
            raise _persistence_unavailable() from error
        if context.knowledge_base_id != principal.knowledge_base_id:
            raise _forbidden()
        return ConversationResponse.from_domain(context)

    return router


def _parse_event_cursor(value: str | None) -> int:
    if value is None:
        return 0
    try:
        sequence = int(value)
    except ValueError as error:
        raise _invalid_event_cursor() from error
    if sequence < 0:
        raise _invalid_event_cursor()
    return sequence


async def _encode_sse(events: AsyncIterator[RunEvent | None]) -> AsyncIterator[str]:
    async for event in events:
        if event is None:
            yield ": keep-alive\n\n"
            continue
        response = RunEventResponse.from_domain(event)
        payload = json.dumps(
            response.model_dump(by_alias=True, mode="json"),
            ensure_ascii=False,
            separators=(",", ":"),
        )
        yield f"id: {event.sequence}\nevent: {event.event_type}\ndata: {payload}\n\n"


def _invalid_event_cursor() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail={
            "code": "INVALID_EVENT_CURSOR",
            "message": "Last-Event-ID must be a non-negative integer",
        },
    )


def _authorize(
    service: RunApplication | None,
    verifier: PrincipalTokenVerifier | None,
    token: str | None,
) -> tuple[RunApplication, AgentPrincipal]:
    if service is None or verifier is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "RUN_SERVICE_NOT_CONFIGURED", "message": "Run service is unavailable"},
        )
    if token is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "PRINCIPAL_REQUIRED", "message": "Principal token is required"},
        )
    try:
        return service, verifier.verify(token)
    except InvalidPrincipalToken as error:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "INVALID_PRINCIPAL", "message": "Principal token is invalid"},
        ) from error


def _forbidden() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail={
            "code": "KNOWLEDGE_BASE_FORBIDDEN",
            "message": "Knowledge base is outside principal scope",
        },
    )


def _persistence_unavailable() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail={
            "code": "RUN_PERSISTENCE_UNAVAILABLE",
            "message": "Run persistence is unavailable",
        },
    )
