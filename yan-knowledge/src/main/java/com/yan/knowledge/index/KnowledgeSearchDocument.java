package com.yan.knowledge.index;

public record KnowledgeSearchDocument(
        String chunkId,
        Long documentId,
        Long documentVersionId,
        String sourceType,
        String sourceUri,
        String title,
        String section,
        String content,
        Long articleId,
        String visibility,
        String embeddingProvider,
        String embeddingModel,
        Integer embeddingDimensions,
        String chunkingPolicyVersion,
        String keywordSchemaVersion,
        java.util.List<Float> contentVector
) {
}
