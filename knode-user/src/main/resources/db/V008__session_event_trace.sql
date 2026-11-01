-- Apply only to a separately authorized User database; not auto-executed.
-- Existing intents retain v1; no recovery thread may invent their producer context.
ALTER TABLE auth_revocation_intent ADD COLUMN event_schema_version INT NOT NULL DEFAULT 1;
ALTER TABLE auth_revocation_intent ADD COLUMN traceparent VARCHAR(512);
