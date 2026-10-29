package com.yan.knowledge.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ElasticsearchKnowledgeIndexerTest {

    @Test
    void writesArticleIdOnlyForArticleSources() {
        assertThat(ElasticsearchKnowledgeIndexer.articleIdFor("ARTICLE", "7")).isEqualTo(7L);
        assertThat(ElasticsearchKnowledgeIndexer.articleIdFor("USER_DOCUMENT", "1:document-uuid")).isNull();
    }
}
