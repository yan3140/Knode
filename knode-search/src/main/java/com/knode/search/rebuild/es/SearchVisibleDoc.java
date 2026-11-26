package com.knode.search.rebuild.es;

import com.fasterxml.jackson.databind.JsonNode;

public record SearchVisibleDoc(
        String docId,
        Long version,
        JsonNode source
) {
}
