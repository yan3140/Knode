package com.yan.knowledge.index;

import static org.assertj.core.api.Assertions.assertThat;
import com.yan.knowledge.core.retrieval.ExactMatchType;
import org.junit.jupiter.api.Test;

class KnodeExactSignalExtractorTest {
    @Test void detectsCodeAndOperationalIdentifiers() {
        assertThat(new KnodeExactSignalExtractor().extract("NullPointerException at /api/blog/knowledge port 9200"))
                .extracting(signal -> signal.value()).contains("NullPointerException", "/api/blog/knowledge", "9200");
    }

    @Test void keepsAHyphenatedErrorCodeAsOneSignal() {
        assertThat(new KnodeExactSignalExtractor().extract("KNODE-E2E-409"))
                .containsExactly(new com.yan.knowledge.core.retrieval.ExactSignal(
                        ExactMatchType.ERROR_CODE, "KNODE-E2E-409"));
    }
}
