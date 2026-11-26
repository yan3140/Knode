package com.knode.search.rebuild.es;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SearchIndexAdminClient {

    static String attachFenceAlias(String physicalIndex) {
        if (physicalIndex == null || physicalIndex.isBlank()) {
            throw new IllegalArgumentException("physicalIndex must be non-blank");
        }
        return physicalIndex.trim() + "__attach_fence";
    }

    void ensurePhysicalIndex(String physicalIndex);

    void refreshIndex(String physicalIndex);

    long countDocumentsBySearchable(String physicalIndex, boolean searchable);

    Map<String, SearchVisibleDoc> fetchSearchVisibleDocsByIds(String physicalIndex, List<String> docIds);

    Set<String> getAliasTargets(String aliasName);

    AliasSwitchResult switchAliasAtomically(String aliasName, Set<String> removeFromIndices, String targetPhysicalIndex);

    default AliasSwitchResult switchAliasAtomicallyWithFence(
            String aliasName,
            Set<String> removeFromIndices,
            String targetPhysicalIndex,
            String attachFenceAlias) {
        return switchAliasAtomically(aliasName, removeFromIndices, targetPhysicalIndex);
    }

    AliasSwitchResult removeAliasBindingsAtomically(String aliasName, Set<String> removeFromIndices);
}
