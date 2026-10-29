from datetime import datetime
from typing import Protocol

from yan_agent.domain.errors import AgentErrorCode
from yan_agent.domain.qa import AgentResult
from yan_agent.domain.runs import (
    ConversationContext,
    CreateRunCommand,
    EnqueuedRun,
    RunEvent,
    RunRecord,
    UpdatedRun,
)


class RunRepository(Protocol):
    async def enqueue(
        self,
        command: CreateRunCommand,
        run_id: str,
        message_id: str,
    ) -> EnqueuedRun: ...

    async def load_context(self, conversation_id: str, user_id: int) -> ConversationContext: ...

    async def get_run(self, run_id: str, user_id: int) -> RunRecord: ...

    async def list_events(
        self,
        run_id: str,
        user_id: int,
        after_sequence: int = 0,
    ) -> list[RunEvent]: ...

    async def claim_next(
        self,
        worker_id: str,
        now: datetime,
        lease_until: datetime,
    ) -> RunRecord | None: ...

    async def renew_lease(
        self,
        run_id: str,
        worker_id: str,
        now: datetime,
        lease_until: datetime,
    ) -> bool: ...

    async def complete(
        self,
        run_id: str,
        worker_id: str,
        result: AgentResult,
        message_id: str,
        now: datetime,
    ) -> ConversationContext: ...

    async def mark_waiting(
        self,
        run_id: str,
        worker_id: str,
        result: AgentResult,
    ) -> ConversationContext: ...

    async def fail(
        self,
        run_id: str,
        worker_id: str,
        error_code: AgentErrorCode,
        retryable: bool,
        safe_message: str,
        now: datetime,
    ) -> ConversationContext: ...

    async def resume(
        self,
        run_id: str,
        user_id: int,
        answer: str,
        message_id: str,
        now: datetime,
    ) -> UpdatedRun: ...
