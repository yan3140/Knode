import asyncio
import json
import logging
import time
from collections.abc import Callable
from datetime import datetime, timedelta
from typing import Protocol

from opentelemetry import trace
from opentelemetry.trace import Status, StatusCode

from yan_agent.application.context_manager import ContextManager
from yan_agent.application.error_classifier import classify_agent_error
from yan_agent.application.run_event_notifications import (
    NullRunEventNotifier,
    publish_best_effort,
)
from yan_agent.domain.qa import AgentInvocation, AgentResult, AgentStatus
from yan_agent.domain.runs import ConversationContext, RunLeaseLost, RunRecord
from yan_agent.observability.context import bind_context
from yan_agent.observability.metrics import AgentMetrics
from yan_agent.observability.tracing import extracted_trace_context
from yan_agent.ports.run_events import RunEventNotifier
from yan_agent.ports.runs import RunRepository

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


class CheckpointedAgent(Protocol):
    async def invoke(self, invocation: AgentInvocation) -> AgentResult: ...

    async def resume(self, run_id: str, answer: str) -> AgentResult: ...

    async def recover(self, invocation: AgentInvocation) -> AgentResult: ...


class RunWorker:
    def __init__(
        self,
        repository: RunRepository,
        contexts: ContextManager,
        agent: CheckpointedAgent,
        new_id: Callable[[], str],
        clock: Callable[[], datetime],
        lease_seconds: int = 30,
        event_notifier: RunEventNotifier | None = None,
        metrics: AgentMetrics | None = None,
    ) -> None:
        self._repository = repository
        self._contexts = contexts
        self._agent = agent
        self._new_id = new_id
        self._clock = clock
        self._lease_seconds = lease_seconds
        self._event_notifier = event_notifier or NullRunEventNotifier()
        self._metrics = metrics

    async def run_once(self, worker_id: str) -> bool:
        now = self._clock()
        run = await self._repository.claim_next(
            worker_id=worker_id,
            now=now,
            lease_until=now + timedelta(seconds=self._lease_seconds),
        )
        if run is None:
            return False
        await publish_best_effort(self._event_notifier, run.id)
        started_at = time.perf_counter()
        metric_status = "error"
        degraded = False
        effective_strategy = None

        parent_context = extracted_trace_context(run.traceparent, run.tracestate)
        with (
            bind_context(run_id=run.id),
            tracer.start_as_current_span(
                "agent.run.execute",
                context=parent_context,
            ) as span,
        ):
            span.set_attribute("agent.run.id", run.id)
            span.set_attribute("agent.thread.id", run.id)
            span.set_attribute("agent.knowledge_base.id", run.knowledge_base_id)
            span.set_attribute("agent.run.attempt", run.attempt_count)
            span.set_attribute(
                "agent.queue.delay_ms",
                max(0.0, (now - run.created_at).total_seconds() * 1000),
            )
            try:
                context = await self._contexts.load(
                    run.conversation_id,
                    run.user_id,
                    minimum_version=run.context_version,
                )
                if self._metrics is not None:
                    self._metrics.observe_context("l1", "assembled")
                invocation = AgentInvocation(
                    run_id=run.id,
                    user_id=run.user_id,
                    knowledge_base_id=run.knowledge_base_id,
                    question=run.question,
                    requested_mode=run.requested_mode,
                    conversation_context=_assemble_l1_context(context),
                    context_version=context.context_version,
                )
                result = await self._execute_with_heartbeat(run, worker_id, invocation)
                degraded = result.degraded
                if result.routing_decision is not None:
                    effective_strategy = result.routing_decision.effective_strategy
                if result.status is AgentStatus.CLARIFICATION_REQUIRED:
                    metric_status = "WAITING_FOR_USER"
                    committed = await self._repository.mark_waiting(
                        run_id=run.id,
                        worker_id=worker_id,
                        result=result,
                    )
                else:
                    metric_status = result.status.value
                    committed = await self._repository.complete(
                        run_id=run.id,
                        worker_id=worker_id,
                        result=result,
                        message_id=self._new_id(),
                        now=self._clock(),
                    )
            except (asyncio.CancelledError, RunLeaseLost) as error:
                metric_status = (
                    "cancelled" if isinstance(error, asyncio.CancelledError) else "lease_lost"
                )
                span.set_status(Status(StatusCode.ERROR, metric_status))
                raise
            except Exception as error:
                metric_status = "FAILED"
                classified = classify_agent_error(error)
                span.set_attribute("error.type", type(error).__name__)
                span.set_attribute("agent.error.code", classified.code)
                span.set_status(Status(StatusCode.ERROR, classified.code))
                committed = await self._repository.fail(
                    run_id=run.id,
                    worker_id=worker_id,
                    error_code=classified.code,
                    retryable=classified.retryable,
                    safe_message=classified.safe_message,
                    now=self._clock(),
                )
            span.set_attribute("agent.run.status", metric_status)
            span.set_attribute("agent.run.degraded", degraded)
            await self._contexts.publish_committed(committed)
            await publish_best_effort(self._event_notifier, run.id)
            duration = time.perf_counter() - started_at
            logger.info(
                "Agent run completed",
                extra={
                    "event": "agent_run_completed",
                    "status": metric_status,
                    "duration_ms": round(duration * 1000, 3),
                    "worker_id": worker_id,
                },
            )
            if self._metrics is not None:
                self._metrics.observe_run(
                    metric_status,
                    degraded,
                    duration,
                    effective_strategy,
                )
        return True

    async def _execute_with_heartbeat(
        self,
        run: RunRecord,
        worker_id: str,
        invocation: AgentInvocation,
    ) -> AgentResult:
        stop = asyncio.Event()
        execution = asyncio.create_task(self._execute_agent(run, invocation))
        heartbeat = asyncio.create_task(self._heartbeat(run.id, worker_id, stop))
        done, _pending = await asyncio.wait(
            {execution, heartbeat},
            return_when=asyncio.FIRST_COMPLETED,
        )
        if execution in done:
            stop.set()
            await heartbeat
            return execution.result()
        execution.cancel()
        try:
            await execution
        except asyncio.CancelledError:
            pass
        await heartbeat
        raise RuntimeError("Heartbeat stopped unexpectedly")

    async def _execute_agent(
        self,
        run: RunRecord,
        invocation: AgentInvocation,
    ) -> AgentResult:
        if run.resume_value is not None:
            return await self._agent.resume(run.id, run.resume_value)
        if run.attempt_count > 1:
            return await self._agent.recover(invocation)
        return await self._agent.invoke(invocation)

    async def _heartbeat(self, run_id: str, worker_id: str, stop: asyncio.Event) -> None:
        interval = max(1.0, self._lease_seconds / 3)
        while True:
            try:
                await asyncio.wait_for(stop.wait(), timeout=interval)
                return
            except TimeoutError:
                now = self._clock()
                renewed = await self._repository.renew_lease(
                    run_id=run_id,
                    worker_id=worker_id,
                    now=now,
                    lease_until=now + timedelta(seconds=self._lease_seconds),
                )
                if not renewed:
                    raise RunLeaseLost("Worker lease expired during Agent execution") from None


def _assemble_l1_context(context: ConversationContext) -> str:
    payload = {
        "summary": context.summary,
        "contextVersion": context.context_version,
        "messages": [
            {"role": message.role.value, "content": message.content} for message in context.messages
        ],
    }
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
