-- Knode 知识库事实数据和可靠索引任务（MySQL 8.0，单次执行）
-- 文档版本是不可变事实；Elasticsearch 和 Redis Stream 是后续派生 Adapter。

CREATE TABLE yan_knowledge_base (
    id BIGINT NOT NULL AUTO_INCREMENT,
    type VARCHAR(16) NOT NULL,
    owner_id BIGINT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_knowledge_base_owner (owner_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库定义';

INSERT INTO yan_knowledge_base(id, type, owner_id, name, description)
VALUES(1, 'SYSTEM', NULL, '系统知识库', '全站公开已发布文章')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

CREATE TABLE yan_knowledge_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    source_uri VARCHAR(500) NULL,
    current_version_id BIGINT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_document_source (source_type, source_id),
    KEY idx_knowledge_document_current_version (current_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库逻辑文档';

CREATE TABLE yan_knowledge_document_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL,
    source_uri VARCHAR(500) NULL,
    status VARCHAR(32) NOT NULL,
    keyword_enabled TINYINT NOT NULL,
    vector_enabled TINYINT NOT NULL,
    keyword_availability VARCHAR(32) NOT NULL,
    vector_availability VARCHAR(32) NOT NULL,
    embedding_provider VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    embedding_dimensions INT NOT NULL,
    chunking_policy_version VARCHAR(64) NOT NULL,
    keyword_schema_version VARCHAR(64) NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_version_number (document_id, version_no),
    UNIQUE KEY uk_knowledge_version_hash (document_id, content_hash),
    KEY idx_knowledge_version_status (status, document_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库不可变文档版本';

CREATE TABLE yan_knowledge_base_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_base_document (knowledge_base_id, document_id),
    KEY idx_knowledge_base_document_document (document_id, knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库与逻辑文档关联';

CREATE TABLE yan_knowledge_chunk (
    id VARCHAR(192) NOT NULL,
    document_id BIGINT NOT NULL,
    document_version_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    section_name VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    source_uri VARCHAR(500) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_chunk_version_index (document_version_id, chunk_index),
    KEY idx_knowledge_chunk_document (document_id, document_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库切片事实记录';

CREATE TABLE yan_knowledge_query (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    user_id BIGINT NULL,
    query_text VARCHAR(2000) NOT NULL,
    requested_mode VARCHAR(32) NOT NULL,
    selected_mode VARCHAR(32) NULL,
    degraded TINYINT NOT NULL DEFAULT 0,
    degradation_reasons VARCHAR(500) NULL,
    total_elapsed_millis BIGINT NULL,
    trace_id VARCHAR(128) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_knowledge_query_base_time (knowledge_base_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库检索记录';

CREATE TABLE yan_knowledge_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    query_id BIGINT NOT NULL,
    user_id BIGINT NULL,
    helpful TINYINT NOT NULL,
    comment VARCHAR(1000) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_knowledge_feedback_query (query_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库检索反馈';

CREATE TABLE yan_knowledge_index_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    document_version_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    task_key VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry INT NOT NULL DEFAULT 8,
    next_retry_at DATETIME(3) NOT NULL,
    locked_until DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    dead_letter_at DATETIME(3) NULL,
    process_count INT NOT NULL DEFAULT 0,
    last_process_at DATETIME(3) NULL,
    rerun_requested TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_index_outbox_task_key (task_key),
    KEY idx_knowledge_index_outbox_pending (status, next_retry_at, id),
    KEY idx_knowledge_index_outbox_claim (status, locked_until),
    KEY idx_knowledge_index_outbox_version (document_id, document_version_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库索引可靠投递事件';
