package com.yan.knowledge.core.domain;

/** 文档版本的发布状态；状态迁移由公共内核统一约束，Adapter 只负责持久化。 */
public enum DocumentVersionStatus {
    RECEIVED,
    INDEXING,
    READY,
    PARTIAL_READY,
    FAILED,
    DELETED;

    public boolean canTransitionTo(DocumentVersionStatus target) {
        return switch (this) {
            case RECEIVED -> target == INDEXING || target == DELETED;
            case INDEXING -> target == READY || target == PARTIAL_READY || target == FAILED;
            case PARTIAL_READY -> target == INDEXING || target == DELETED;
            case FAILED -> target == INDEXING || target == DELETED;
            case READY -> target == DELETED;
            case DELETED -> false;
        };
    }

    public boolean isUsable() {
        return this == READY || this == PARTIAL_READY;
    }
}
