package com.knode.search.model;

import java.util.List;

public record SearchCandidatePage(
        List<SearchCandidateRow> rows,
        long total
) {
}
