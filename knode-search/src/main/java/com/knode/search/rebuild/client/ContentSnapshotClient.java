package com.knode.search.rebuild.client;

import com.knode.search.model.QueryBudget;

public interface ContentSnapshotClient {

    SnapshotHandle createSearchRebuildSnapshot(String clientRequestId, QueryBudget budget, String requestId);

    SnapshotPage fetchSnapshotPage(String snapshotId, String cursor, int limit, QueryBudget budget, String requestId);

    ChangeFeedPage fetchArticleChanges(String cursor, int limit, QueryBudget budget, String requestId);
}
