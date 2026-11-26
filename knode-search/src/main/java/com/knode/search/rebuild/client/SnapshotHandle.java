package com.knode.search.rebuild.client;

import java.time.Instant;

public record SnapshotHandle(
        String snapshotId,
        String incrementalCursor,
        Instant expiresAt
) {
}
