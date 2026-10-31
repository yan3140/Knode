-- Apply only to a separately authorized G4 user database; never auto-executed.
ALTER TABLE sys_user ADD COLUMN profile_revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE sys_user ADD COLUMN profile_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
