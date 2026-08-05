package com.yan.search.repository;

import com.yan.search.model.ArticleSearchDocument;
import com.yan.search.model.ArticleSearchPage;
import com.yan.search.model.SearchQuery;
import com.yan.search.model.SearchSort;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ArticleSearchRepository {
    void ensureIndex() throws IOException;

    Set<Long> bulkSynchronize(List<ArticleSearchDocument> documents,
                              Collection<Long> deleteArticleIds) throws IOException;

    void rebuild(List<ArticleSearchDocument> documents) throws IOException;

    ArticleSearchPage search(SearchQuery query) throws IOException;
}
