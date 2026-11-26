package com.knode.search.rebuild.client;

import java.util.List;

public record ChangeCommitBatch(
        long commitSequence,
        List<String> rawEventJsons
) {
}
