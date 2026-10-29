package com.yan.knowledge.core.domain;

/** 创建文档版本时冻结的索引启用快照，任务执行时不能重新读取全局配置。 */
public record IndexProfile(
        boolean keywordEnabled,
        boolean vectorEnabled
) {
    public IndexProfile {
        if (!keywordEnabled && !vectorEnabled) {
            throw new IllegalArgumentException("至少需要启用一种知识索引");
        }
    }
}
