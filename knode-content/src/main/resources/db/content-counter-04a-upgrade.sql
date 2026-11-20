-- Manual structural change for the counter event stream; never auto-run.
CREATE TABLE content_article_counter_event (
 event_id VARCHAR(36) PRIMARY KEY, article_id BIGINT NOT NULL, counter_revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_content_article_counter_revision UNIQUE(article_id,counter_revision),
 CONSTRAINT fk_content_counter_event_article FOREIGN KEY (article_id) REFERENCES content_article(id)
);
