-- Structural DDL only. Never auto-applied. MySQL 8; H2 tests use MODE=MySQL.
CREATE TABLE content_category (
 id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, status CHAR(1) NOT NULL, description VARCHAR(1024)
);
CREATE TABLE content_tag (
 id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, del_flag SMALLINT NOT NULL DEFAULT 0, remark VARCHAR(1024)
);
CREATE TABLE content_article (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 title VARCHAR(256) NOT NULL, content LONGTEXT NOT NULL, summary VARCHAR(1024),
 thumbnail VARCHAR(2048), category_id BIGINT NOT NULL, is_top CHAR(1) NOT NULL,
 is_comment CHAR(1) NOT NULL, status CHAR(1) NOT NULL, author_id VARCHAR(19) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, updated_by VARCHAR(19) NOT NULL,
 view_count BIGINT NOT NULL DEFAULT 0, counter_revision BIGINT NOT NULL DEFAULT 0,
 source_revision BIGINT NOT NULL, publication_fact_id VARCHAR(36), published_at TIMESTAMP(6),
 deleted SMALLINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_content_publication_fact UNIQUE (publication_fact_id),
 CONSTRAINT fk_article_category FOREIGN KEY (category_id) REFERENCES content_category(id)
);
CREATE TABLE content_article_tag (
 article_id BIGINT NOT NULL, tag_id BIGINT NOT NULL,
 PRIMARY KEY (article_id, tag_id),
 CONSTRAINT fk_article_tag_article FOREIGN KEY (article_id) REFERENCES content_article(id),
 CONSTRAINT fk_article_tag_tag FOREIGN KEY (tag_id) REFERENCES content_tag(id)
);
CREATE TABLE content_article_event (
 event_id VARCHAR(36) PRIMARY KEY, article_id BIGINT NOT NULL, source_revision BIGINT NOT NULL,
 event_type VARCHAR(64) NOT NULL, payload LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_content_article_event_revision UNIQUE(article_id, source_revision),
 CONSTRAINT fk_content_event_article FOREIGN KEY (article_id) REFERENCES content_article(id)
);
