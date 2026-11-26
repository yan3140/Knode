package com.knode.search.rebuild.client;

import java.util.List;

public record SnapshotPage(
        String snapshotId,
        List<SnapshotArticleItem> items,
        String nextCursor,
        boolean done,
        String incrementalCursor
) {
}
