import os
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from yan_agent.adapters.persistence.run_repository import PostgresRunRepository
from yan_agent.application.run_service import RunService
from yan_agent.domain.qa import AgentResult, AgentStatus
from yan_agent.domain.runs import CreateRunCommand, RunStatus
from yan_agent.ids import new_ulid

TEST_DSN = os.getenv("YAN_AGENT_TEST_POSTGRES_DSN")
pytestmark = pytest.mark.skipif(TEST_DSN is None, reason="PostgreSQL integration DSN is not set")


@pytest.mark.asyncio
async def test_postgres_run_flow_is_idempotent_and_commits_one_answer() -> None:
    assert TEST_DSN is not None
    engine = create_async_engine(TEST_DSN.replace("postgresql://", "postgresql+psycopg://", 1))
    repository = PostgresRunRepository(async_sessionmaker(engine, expire_on_commit=False))
    service = RunService(repository, new_ulid)
    command = CreateRunCommand(
        client_request_id=new_ulid(),
        conversation_id=new_ulid(),
        user_id=3001,
        knowledge_base_id=5001,
        question="持久化测试问题",
        traceparent="00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        tracestate="vendor=opaque",
    )
    now = datetime.now(UTC)
    try:
        first = await service.enqueue(command)
        duplicate = await service.enqueue(command)
        claimed = await repository.claim_next(
            "integration-worker",
            now,
            now + timedelta(seconds=30),
        )
        assert claimed is not None
        result = AgentResult(
            run_id=claimed.id,
            status=AgentStatus.SUCCEEDED,
            answer="持久化测试回答",
            citations=[],
            query_ids=[901],
        )

        await repository.complete(claimed.id, "integration-worker", result, new_ulid(), now)
        await repository.complete(claimed.id, "integration-worker", result, new_ulid(), now)
        completed = await service.get_run(claimed.id, user_id=3001)
        events = await service.list_events(claimed.id, user_id=3001)
        context = await service.load_context(command.conversation_id, user_id=3001)
    finally:
        await engine.dispose()

    assert first.run.id == duplicate.run.id
    assert duplicate.created is False
    assert first.run.traceparent == command.traceparent
    assert duplicate.run.tracestate == command.tracestate
    assert claimed.traceparent == command.traceparent
    assert claimed.tracestate == command.tracestate
    assert completed.status is RunStatus.SUCCEEDED
    assert [event.event_type for event in events] == [
        "run.queued",
        "run.started",
        "retrieval.completed",
        "answer.delta",
        "citations.final",
        "run.completed",
    ]
    assert context.context_version == 2
    assert [message.content for message in context.messages] == [
        "持久化测试问题",
        "持久化测试回答",
    ]
