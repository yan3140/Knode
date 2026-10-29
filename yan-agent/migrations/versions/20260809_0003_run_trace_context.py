"""persist W3C trace context across the durable run queue

Revision ID: 20260809_0003
Revises: 20260807_0002
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260809_0003"
down_revision: str | None = "20260807_0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("agent_run", sa.Column("traceparent", sa.String(length=512), nullable=True))
    op.add_column("agent_run", sa.Column("tracestate", sa.String(length=512), nullable=True))


def downgrade() -> None:
    op.drop_column("agent_run", "tracestate")
    op.drop_column("agent_run", "traceparent")
