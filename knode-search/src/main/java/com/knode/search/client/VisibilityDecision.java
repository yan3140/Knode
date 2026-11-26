package com.knode.search.client;

public record VisibilityDecision(
        String articleId,
        String sourceRevision,
        String visibility,
        String decision
) {
    public boolean isPublicReadableMatchingRevision(String expectedSourceRevision) {
        return "PUBLIC".equals(visibility)
                && "READABLE".equals(decision)
                && sourceRevision != null
                && expectedSourceRevision != null
                && sourceRevision.equals(expectedSourceRevision);
    }
}
