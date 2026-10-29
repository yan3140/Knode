from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from yan_agent.domain.qa import RetrievalMode

GRAPH_VERSION = "knowledge-qa.v3"


class RunDomainModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class RunStatus(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    WAITING_INPUT = "WAITING_INPUT"
    SUCCEEDED = "SUCCEEDED"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class MessageRole(StrEnum):
    USER = "USER"
    ASSISTANT = "ASSISTANT"


class CreateRunCommand(RunDomainModel):
    client_request_id: str = Field(min_length=1, max_length=64)
    conversation_id: str = Field(min_length=1, max_length=26)
    user_id: int = Field(gt=0)
    knowledge_base_id: int = Field(gt=0)
    question: str = Field(min_length=1, max_length=2000)
    requested_mode: RetrievalMode = RetrievalMode.AUTO
    traceparent: str | None = Field(default=None, max_length=512)
    tracestate: str | None = Field(default=None, max_length=512)


class ConversationMessage(RunDomainModel):
    id: str
    run_id: str | None
    role: MessageRole
    content: str
    citations: list[dict[str, object]] = Field(default_factory=list)
    created_at: datetime


class ConversationContext(RunDomainModel):
    conversation_id: str
    user_id: int
    knowledge_base_id: int
    summary: str | None
    context_version: int = Field(ge=0)
    messages: list[ConversationMessage]


class RunRecord(RunDomainModel):
    id: str
    client_request_id: str
    conversation_id: str
    user_id: int
    knowledge_base_id: int
    question: str
    requested_mode: RetrievalMode
    context_version: int = Field(default=0, ge=0)
    status: RunStatus
    graph_version: str
    current_node: str | None = None
    query_ids: list[int] = Field(default_factory=list)
    error_code: str | None = None
    attempt_count: int = Field(default=0, ge=0)
    lease_owner: str | None = None
    lease_until: datetime | None = None
    resume_value: str | None = None
    traceparent: str | None = None
    tracestate: str | None = None
    result: dict[str, object] = Field(default_factory=dict)
    created_at: datetime
    started_at: datetime | None = None
    completed_at: datetime | None = None


class RunEvent(RunDomainModel):
    sequence: int = Field(ge=1)
    event_type: str = Field(min_length=1, max_length=100)
    payload: dict[str, object] = Field(default_factory=dict)
    created_at: datetime


class EnqueuedRun(RunDomainModel):
    run: RunRecord
    context: ConversationContext
    created: bool


class UpdatedRun(RunDomainModel):
    run: RunRecord
    context: ConversationContext


class IdempotencyConflict(ValueError):
    pass


class ConversationAccessDenied(PermissionError):
    pass


class RunNotFound(LookupError):
    pass


class RunLeaseLost(RuntimeError):
    pass
