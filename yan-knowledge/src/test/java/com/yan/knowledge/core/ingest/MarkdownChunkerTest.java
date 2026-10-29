package com.yan.knowledge.core.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    @Test
    void shouldKeepHeadingSectionsAndStableChunkIndexes() {
        ParsedDocument document = new ParsedDocument(
                "运行手册",
                "runbook.md",
                "# 数据库\n连接池耗尽时先检查 active 连接。\n\n# 缓存\n缓存击穿时检查 TTL。\n");

        List<ChunkDraft> chunks = new MarkdownChunker().chunk(
                document,
                new ChunkingPolicy(100, 10, "test-v1"));

        assertThat(chunks).extracting(ChunkDraft::section)
                .containsExactly("数据库", "缓存");
        assertThat(chunks).extracting(ChunkDraft::chunkIndex)
                .containsExactly(0, 1);
        assertThat(chunks.get(0).content()).startsWith("数据库\n");
        assertThat(chunks.get(1).metadata()).containsEntry("source", "runbook.md");
    }

    @Test
    void shouldUseTargetSizeAndOverlapForLongSections() {
        String body = "word ".repeat(40).strip();
        List<ChunkDraft> chunks = new MarkdownChunker().chunk(
                new ParsedDocument("手册", "long.md", "# 故障\n" + body),
                new ChunkingPolicy(50, 10, "test-v1"));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(50));
        String prefix = "故障\n";
        String firstBody = chunks.get(0).content().substring(prefix.length());
        String secondBody = chunks.get(1).content().substring(prefix.length());
        assertThat(firstBody).endsWith(secondBody.substring(0, 10));
    }

    @Test
    void shouldChunkLongChineseArticleWithDefaultPolicyAndStableMetadata() {
        String body = "并发控制需要明确线程池边界和背压策略。".repeat(50);
        ParsedDocument document = new ParsedDocument("Java 并发实践", "article:42", "# 线程池\r\n" + body);

        List<ChunkDraft> chunks = new MarkdownChunker().chunk(document, ChunkingPolicy.defaultPolicy());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).hasSizeLessThanOrEqualTo(700);
            assertThat(chunk.section()).isEqualTo("线程池");
            assertThat(chunk.metadata()).containsEntry("source", "article:42");
        });
        assertThat(chunks).extracting(ChunkDraft::chunkIndex)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());

        String prefix = "线程池\n";
        String firstBody = chunks.get(0).content().substring(prefix.length());
        String secondBody = chunks.get(1).content().substring(prefix.length());
        assertThat(firstBody.substring(firstBody.length() - 100)).isEqualTo(secondBody.substring(0, 100));
    }
}
