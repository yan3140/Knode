package com.knode.search.repository;

import com.knode.search.model.QueryBudget;
import com.knode.search.model.SearchCandidatePage;
import com.knode.search.model.SearchQuery;

import java.io.IOException;

public interface ArticleSearchRepository {

    SearchCandidatePage search(SearchQuery query, QueryBudget budget) throws IOException;
}
