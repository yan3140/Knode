"""Add durable Agent run input and recovery state.

Revision ID: 20260807_0002
Revises: 20260807_0001
Create Date: 2026-08-07
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260807_0002"
down_revision: str | None = "20260807_0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "agent_run",
        sa.Column("question", sa.Text(), server_default="", nullable=False),
    )
    op.alter_column("agent_run", "question", server_default=None)
    op.add_column(
        "agent_run",
        sa.Column("requested_mode", sa.String(length=32), server_default="AUTO", nullable=False),
    )
    op.add_column(
        "agent_run",
        sa.Column("context_version", sa.BigInteger(), server_default="0", nullable=False),
    )
    op.add_column("agent_run", sa.Column("resume_value", sa.Text(), nullable=True))
    op.add_column(
        "agent_run",
        sa.Column(
            "result",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_column("agent_run", "result")
    op.drop_column("agent_run", "resume_value")
    op.drop_column("agent_run", "context_version")
    op.drop_column("agent_run", "requested_mode")
    op.drop_column("agent_run", "question")
