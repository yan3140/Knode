package com.yan.knowledge.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yan.knowledge.core.domain.IndexIdentity;
import com.yan.knowledge.core.domain.IndexOperation;
import com.yan.knowledge.core.domain.IndexTask;
import com.yan.knowledge.mapper.KnowledgeIndexOutboxMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MysqlKnowledgeIndexTaskGatewayTest {

    @Test
    void persistsTaskUsingItsStableIdentity() {
        KnowledgeIndexOutboxMapper mapper = Mockito.mock(KnowledgeIndexOutboxMapper.class);
        KnowledgeOutboxProperties properties = new KnowledgeOutboxProperties();
        when(mapper.enqueue("KNOWLEDGE_INDEX:11:21", 11L, 21L, "INDEX", 8)).thenReturn(1);

        new MysqlKnowledgeIndexTaskGateway(mapper, properties).enqueue(task());

        verify(mapper).enqueue("KNOWLEDGE_INDEX:11:21", 11L, 21L, "INDEX", 8);
    }

    @Test
    void rejectsMissingOutboxPersistence() {
        KnowledgeIndexOutboxMapper mapper = Mockito.mock(KnowledgeIndexOutboxMapper.class);
        when(mapper.enqueue("KNOWLEDGE_INDEX:11:21", 11L, 21L, "INDEX", 8)).thenReturn(0);

        assertThatThrownBy(() -> new MysqlKnowledgeIndexTaskGateway(mapper, new KnowledgeOutboxProperties())
                .enqueue(task()))
                .isInstanceOf(IllegalStateException.class);
    }

    private IndexTask task() {
        return new IndexTask("KNOWLEDGE_INDEX:11:21", "11", "21", IndexOperation.INDEX,
                new IndexIdentity("unconfigured", "unconfigured", 0,
                        "markdown-heading-v1", "elasticsearch-standard-v1"), Instant.now());
    }
}
