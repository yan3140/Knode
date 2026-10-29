import os

import psycopg
import pytest

TEST_DSN = os.getenv("YAN_AGENT_TEST_POSTGRES_DSN")
pytestmark = pytest.mark.skipif(TEST_DSN is None, reason="PostgreSQL integration DSN is not set")


def test_foundation_migration_creates_context_versioned_agent_tables() -> None:
    assert TEST_DSN is not None
    with psycopg.connect(TEST_DSN) as connection:
        tables = {
            row[0]
            for row in connection.execute(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'agent_%'
                """
            )
        }
        context_version_nullable = connection.execute(
            """
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'agent_conversation'
              AND column_name = 'context_version'
            """
        ).fetchone()
        default_context_version = connection.execute(
            """
            INSERT INTO agent_conversation (id, user_id, knowledge_base_id)
            VALUES ('migration-default-probe', 1, 1)
            RETURNING context_version
            """
        ).fetchone()
        connection.rollback()
        durable_run_columns = {
            row[0]
            for row in connection.execute(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'agent_run'
                  AND column_name IN (
                    'question', 'requested_mode', 'context_version', 'resume_value', 'result',
                    'traceparent', 'tracestate'
                  )
                """
            )
        }

    assert tables == {
        "agent_conversation",
        "agent_message",
        "agent_run",
        "agent_run_event",
    }
    assert context_version_nullable == ("NO",)
    assert default_context_version == (0,)
    assert durable_run_columns == {
        "question",
        "requested_mode",
        "context_version",
        "resume_value",
        "result",
        "traceparent",
        "tracestate",
    }
