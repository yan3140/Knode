package com.knode.search.rebuild.es;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SearchIndexAdminClient {

    void ensurePhysicalIndex(String physicalIndex);

    void refreshIndex(String physicalIndex);

    long countDocumentsBySearchable(String physicalIndex, boolean searchable);

    Map<String, SearchVisibleDoc> fetchSearchVisibleDocsByIds(String physicalIndex, List<String> docIds);

    Set<String> getAliasTargets(String aliasName);

    void switchAliasAtomically(String aliasName, Set<String> removeFromIndices, String targetPhysicalIndex);
}
