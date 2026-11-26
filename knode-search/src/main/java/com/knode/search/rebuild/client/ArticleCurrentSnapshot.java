package com.knode.search.rebuild.client;

public record ArticleCurrentSnapshot(
        long articleId,
        Long sourceRevision,
        String visibility,
        String decision,
        SnapshotArticleItem snapshot
) {
    public boolean isReadablePublic() {
        return "PUBLIC".equals(visibility) && "READABLE".equals(decision) && snapshot != null && sourceRevision != null;
    }

    public boolean isMissing() {
        return "MISSING".equals(visibility);
    }
}
