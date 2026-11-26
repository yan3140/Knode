package com.knode.search.client;

import com.knode.search.model.QueryBudget;

import java.util.List;

public interface ServiceTokenClient {

    String acquireToken(String audience, List<String> permissions, QueryBudget budget, String requestId);

    void invalidate(String audience, List<String> permissions);
}
