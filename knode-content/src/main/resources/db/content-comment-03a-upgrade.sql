-- Manual structural upgrade from 02C/02D schema only. Do not auto-run.


CREATE TABLE content_comment (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 comment_type CHAR(1) NOT NULL, article_id BIGINT,
 root_id BIGINT NOT NULL, to_comment_id BIGINT NOT NULL, to_user_id VARCHAR(19) NOT NULL,
 actor_id VARCHAR(19) NOT NULL, content VARCHAR(4000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, deleted SMALLINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_content_comment_article FOREIGN KEY (article_id) REFERENCES content_article(id)
);
CREATE INDEX ix_content_comment_thread ON content_comment(comment_type,article_id,deleted,root_id,created_at,id);
CREATE TABLE content_comment_event (
 event_id VARCHAR(36) PRIMARY KEY, comment_id BIGINT NOT NULL, source_revision BIGINT NOT NULL,
 event_type VARCHAR(64) NOT NULL, payload LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_content_comment_event_revision UNIQUE(comment_id,source_revision),
 CONSTRAINT fk_content_comment_event FOREIGN KEY (comment_id) REFERENCES content_comment(id)
);
CREATE TABLE content_comment_limit (
 kind CHAR(1) NOT NULL, key_hash CHAR(64) NOT NULL, hits INT NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(kind,key_hash)
);
CREATE INDEX ix_content_comment_limit_expiry ON content_comment_limit(expires_at,kind,key_hash);
