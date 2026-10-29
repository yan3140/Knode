package com.yan.knowledge.core.domain;

/** 单个索引通道的可用状态；未启用的通道必须使用 NOT_REQUIRED。 */
public enum IndexAvailabilityState {
    NOT_REQUIRED,
    PENDING,
    READY,
    FAILED
}
