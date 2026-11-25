-- Structural DDL upgrade for COMMUNITY-04A (consumer ops & replay audit). Never auto-applied.
ALTER TABLE yan_notification_delivery_outbox
    ADD COLUMN event_id VARCHAR(64) NULL AFTER id;
CREATE INDEX idx_notification_delivery_event_id ON yan_notification_delivery_outbox (event_id);

ALTER TABLE yan_notification_outbox
    ADD COLUMN event_id VARCHAR(64) NULL AFTER id;
CREATE INDEX idx_notification_outbox_event_id ON yan_notification_outbox (event_id);

CREATE TABLE community_ops_replay_audit (
    replay_key_sha256 VARCHAR(64) NOT NULL PRIMARY KEY,
    replay_request_id VARCHAR(64) NOT NULL,
    target_scope VARCHAR(80) NOT NULL,
    service_subject VARCHAR(128) NOT NULL,
    operator_actor VARCHAR(128) NULL,
    result_status VARCHAR(20) NOT NULL,
    requeued_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX idx_community_ops_replay_created ON community_ops_replay_audit (created_at);
