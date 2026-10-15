"""initial schema

Baseline revision: creates the five liveness/PAD tables, their indexes and the
native PostgreSQL enum types backing them. Generated with
``alembic revision --autogenerate`` against an empty PostgreSQL 16 database and
reviewed; ``alembic check`` reports no drift from ``app.db.base.Base.metadata``.

``downgrade()`` drops the enum types explicitly. Autogenerate only emits
``drop_table``, which leaves the types behind, so a downgrade followed by an
upgrade used to fail with ``DuplicateObject: type ... already exists``.

Revision ID: 633dd91383c5
Revises:
Create Date: 2026-10-08 20:15:26.137166

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = '633dd91383c5'
down_revision = None
branch_labels = None
depends_on = None

# Native enum types created by this revision, keyed by the table that owns them.
# Each entry is (type name, values) and must mirror the sa.Enum(...) declarations
# in the models; they are dropped in reverse dependency order in downgrade().
ENUM_TYPES = (
    ('config_workflow_type', ('RESIDENT', 'OPERATOR', 'SUPERVISOR')),
    ('failure_policy', ('LOCK', 'ESCALATE', 'ALLOW_RETRY')),
    ('workflow_type', ('RESIDENT', 'OPERATOR', 'SUPERVISOR')),
    ('session_status', ('ACTIVE', 'PASSED', 'FAILED', 'EXPIRED')),
    ('liveness_stage', ('PASSIVE', 'ACTIVE', 'COMPLETED')),
    ('frame_stage', ('PASSIVE', 'ACTIVE', 'COMPLETED')),
    ('challenge_type', ('BLINK', 'SMILE', 'TURN_LEFT', 'TURN_RIGHT', 'LOOK_DIRECTION')),
    ('challenge_status', ('ISSUED', 'PASSED', 'FAILED', 'TIMEOUT')),
)


def upgrade() -> None:
    op.create_table('config_policies',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('workflow_type', sa.Enum('RESIDENT', 'OPERATOR', 'SUPERVISOR', name='config_workflow_type'), nullable=False),
    sa.Column('liveness_enabled', sa.Boolean(), nullable=False),
    sa.Column('passive_threshold', sa.Float(), nullable=False),
    sa.Column('active_liveness_enabled', sa.Boolean(), nullable=False),
    sa.Column('min_challenge_count', sa.Integer(), nullable=False),
    sa.Column('challenge_types', sa.JSON().with_variant(postgresql.JSONB(astext_type=sa.Text()), 'postgresql'), nullable=False),
    sa.Column('challenge_timeout_ms', sa.Integer(), nullable=False),
    sa.Column('max_retry_count', sa.Integer(), nullable=False),
    sa.Column('on_repeated_failure', sa.Enum('LOCK', 'ESCALATE', 'ALLOW_RETRY', name='failure_policy'), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('workflow_type')
    )
    op.create_table('liveness_sessions',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('workflow_type', sa.Enum('RESIDENT', 'OPERATOR', 'SUPERVISOR', name='workflow_type'), nullable=False),
    sa.Column('device_id', sa.String(length=128), nullable=False),
    sa.Column('subject_ref', sa.String(length=128), nullable=True),
    sa.Column('status', sa.Enum('ACTIVE', 'PASSED', 'FAILED', 'EXPIRED', name='session_status'), nullable=False),
    sa.Column('current_stage', sa.Enum('PASSIVE', 'ACTIVE', 'COMPLETED', name='liveness_stage'), nullable=False),
    sa.Column('retry_count', sa.Integer(), nullable=False),
    sa.Column('online', sa.Boolean(), nullable=False),
    sa.Column('final_result', sa.Boolean(), nullable=True),
    sa.Column('failure_reason', sa.String(length=256), nullable=True),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.Column('closed_at', sa.DateTime(timezone=True), nullable=True),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_liveness_sessions_workflow_type'), 'liveness_sessions', ['workflow_type'], unique=False)
    op.create_table('audit_logs',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('session_id', sa.Uuid(), nullable=False),
    sa.Column('event_type', sa.String(length=64), nullable=False),
    sa.Column('details', sa.JSON().with_variant(postgresql.JSONB(astext_type=sa.Text()), 'postgresql'), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.ForeignKeyConstraint(['session_id'], ['liveness_sessions.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_audit_logs_session_id'), 'audit_logs', ['session_id'], unique=False)
    op.create_table('challenges',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('session_id', sa.Uuid(), nullable=False),
    sa.Column('challenge_type', sa.Enum('BLINK', 'SMILE', 'TURN_LEFT', 'TURN_RIGHT', 'LOOK_DIRECTION', name='challenge_type'), nullable=False),
    sa.Column('status', sa.Enum('ISSUED', 'PASSED', 'FAILED', 'TIMEOUT', name='challenge_status'), nullable=False),
    sa.Column('attempt_number', sa.Integer(), nullable=False),
    sa.Column('timeout_ms', sa.Integer(), nullable=False),
    sa.Column('issued_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.Column('completed_at', sa.DateTime(timezone=True), nullable=True),
    sa.ForeignKeyConstraint(['session_id'], ['liveness_sessions.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_challenges_session_id'), 'challenges', ['session_id'], unique=False)
    op.create_table('frame_events',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('session_id', sa.Uuid(), nullable=False),
    sa.Column('stage', sa.Enum('PASSIVE', 'ACTIVE', 'COMPLETED', name='frame_stage'), nullable=False),
    sa.Column('face_detected', sa.Boolean(), nullable=False),
    sa.Column('multiple_faces', sa.Boolean(), nullable=False),
    sa.Column('face_quality', sa.Float(), nullable=True),
    sa.Column('liveness_score', sa.Float(), nullable=True),
    sa.Column('pad_flag', sa.Boolean(), nullable=False),
    sa.Column('pad_attack_type', sa.String(length=64), nullable=True),
    sa.Column('pad_confidence', sa.Float(), nullable=True),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.ForeignKeyConstraint(['session_id'], ['liveness_sessions.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_frame_events_session_id'), 'frame_events', ['session_id'], unique=False)


def downgrade() -> None:
    op.drop_index(op.f('ix_frame_events_session_id'), table_name='frame_events')
    op.drop_table('frame_events')
    op.drop_index(op.f('ix_challenges_session_id'), table_name='challenges')
    op.drop_table('challenges')
    op.drop_index(op.f('ix_audit_logs_session_id'), table_name='audit_logs')
    op.drop_table('audit_logs')
    op.drop_index(op.f('ix_liveness_sessions_workflow_type'), table_name='liveness_sessions')
    op.drop_table('liveness_sessions')
    op.drop_table('config_policies')
    # drop_table leaves the native enum types behind; drop them so that a
    # downgrade followed by an upgrade does not hit "type already exists".
    for name, values in ENUM_TYPES:
        postgresql.ENUM(*values, name=name).drop(op.get_bind(), checkfirst=True)
