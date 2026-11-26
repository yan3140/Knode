package com.knode.search.consumer.persistence;

public enum SearchIndexTaskStatus {
    PENDING,
    LEASED,
    DONE,
    DEAD
}
