package com.yan.knowledge.core.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkTest {

    @Test
    void shouldBuildStableIdentityFromDocumentVersionAndIndex() {
        assertThat(KnowledgeChunk.stableId("doc-1", "version-2", 3))
                .isEqualTo("doc-1:version-2:3");

        KnowledgeChunk chunk = new KnowledgeChunk(
                "doc-1:version-2:3", "doc-1", "version-2", 3,
                "content", "title", "section", "source", Map.of("chunkIndex", "3"));
        assertThat(chunk.id()).isEqualTo(KnowledgeChunk.stableId(
                chunk.documentId(), chunk.documentVersionId(), chunk.chunkIndex()));
    }
}
