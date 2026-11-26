package com.knode.search.rebuild.client;

import java.time.Instant;
import java.util.List;

public record ChangeFeedPage(
        List<ChangeCommitBatch> commits,
        String nextCursor,
        boolean done,
        Instant cursorExpiresAt
) {
}
