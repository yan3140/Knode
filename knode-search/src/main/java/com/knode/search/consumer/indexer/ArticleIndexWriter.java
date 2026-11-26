package com.knode.search.consumer.indexer;

import com.knode.search.consumer.persistence.SearchIndexTaskRecord;

public interface ArticleIndexWriter {
    IndexWriteResult write(SearchIndexTaskRecord task);
}
