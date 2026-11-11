-- Manual upgrade from 02B schema only; do not run automatically or on fresh 02C schemas.
ALTER TABLE content_tag MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE content_tag MODIFY COLUMN name VARCHAR(256) NOT NULL;
ALTER TABLE content_tag ADD COLUMN created_by VARCHAR(19), ADD COLUMN created_at TIMESTAMP(6), ADD COLUMN updated_by VARCHAR(19), ADD COLUMN updated_at TIMESTAMP(6);
CREATE INDEX ix_content_article_public_list ON content_article(status,deleted,is_top,created_at,id);
CREATE INDEX ix_content_article_public_hot ON content_article(status,deleted,view_count,id);
CREATE INDEX ix_content_article_tag_tag ON content_article_tag(tag_id,article_id);
CREATE TABLE content_write_fence (id BIGINT PRIMARY KEY);
INSERT INTO content_write_fence(id) VALUES (1);
CREATE TABLE content_metadata_job (id VARCHAR(36) PRIMARY KEY, tag_id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, created_at TIMESTAMP(6) NOT NULL);
CREATE TABLE content_metadata_target (job_id VARCHAR(36) NOT NULL, article_id BIGINT NOT NULL, state VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0, next_attempt TIMESTAMP(6) NOT NULL, error_code VARCHAR(32), PRIMARY KEY(job_id,article_id));
CREATE INDEX ix_content_metadata_due ON content_metadata_target(state,next_attempt,job_id,article_id);
CREATE TABLE content_link (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(256) NOT NULL, logo VARCHAR(2048), description VARCHAR(1024), address VARCHAR(2048) NOT NULL, status CHAR(1) NOT NULL, del_flag SMALLINT NOT NULL DEFAULT 0, created_by VARCHAR(19) NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_by VARCHAR(19) NOT NULL, updated_at TIMESTAMP(6) NOT NULL);
