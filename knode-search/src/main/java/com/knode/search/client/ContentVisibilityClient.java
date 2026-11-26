package com.knode.search.client;

import com.knode.search.model.QueryBudget;

import java.util.List;
import java.util.Map;

public interface ContentVisibilityClient {

    Map<String, VisibilityDecision> checkPublicSearch(List<String> articleIds, QueryBudget budget, String requestId);
}
