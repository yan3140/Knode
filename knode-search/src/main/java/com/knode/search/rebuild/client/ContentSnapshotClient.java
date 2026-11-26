package com.knode.search.rebuild.client;

import com.knode.search.model.QueryBudget;

import java.util.List;
import java.util.Map;

public interface ContentSnapshotClient {

    SnapshotHandle createSearchRebuildSnapshot(String clientRequestId, QueryBudget budget, String requestId);

    SnapshotPage fetchSnapshotPage(String snapshotId, String cursor, int limit, QueryBudget budget, String requestId);

    ChangeFeedPage fetchArticleChanges(String cursor, int limit, QueryBudget budget, String requestId);

    default Map<Long, ArticleCurrentSnapshot> fetchCurrentSnapshotsBatch(
            List<Long> articleIds, QueryBudget budget, String requestId) {
        throw new UnsupportedOperationException("fetchCurrentSnapshotsBatch is not supported by this client");
    }
}
