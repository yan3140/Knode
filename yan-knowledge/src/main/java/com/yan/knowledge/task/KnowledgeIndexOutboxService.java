package com.yan.knowledge.task;

import com.yan.knowledge.domain.entity.KnowledgeIndexOutboxEvent;
import com.yan.knowledge.mapper.KnowledgeIndexOutboxMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIndexOutboxService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexOutboxService.class);

    private final KnowledgeIndexOutboxMapper outboxMapper;
    private final KnowledgeIndexOutboxProcessor processor;
    private final KnowledgeOutboxProperties properties;
    private final KnowledgeIndexOutboxMessageGateway messageGateway;

    public KnowledgeIndexOutboxService(KnowledgeIndexOutboxMapper outboxMapper,
                                       KnowledgeIndexOutboxProcessor processor,
                                       KnowledgeOutboxProperties properties,
                                       List<KnowledgeIndexOutboxMessageGateway> messageGateways) {
        this.outboxMapper = outboxMapper;
        this.processor = processor;
        this.properties = properties;
        this.messageGateway = messageGateways.isEmpty() ? null : messageGateways.get(0);
    }

    @Scheduled(fixedDelayString = "${blog.knowledge.outbox.poll-delay-ms:1000}")
    public void deliverPending() {
        if (properties.getLeaseSeconds() < 1 || properties.getBatchSize() < 1) {
            throw new IllegalStateException("Knowledge outbox configuration must be positive");
        }
        outboxMapper.recoverExpiredClaims();
        List<KnowledgeIndexOutboxEvent> claimed = new ArrayList<>();
        for (KnowledgeIndexOutboxEvent candidate : outboxMapper.selectPending(properties.getBatchSize())) {
            if (outboxMapper.claim(candidate.getId(), properties.getLeaseSeconds()) == 1) {
                claimed.add(candidate);
            }
        }
        if (properties.getStream().isEnabled() && messageGateway != null) {
            relayToStream(claimed);
            return;
        }
        if (properties.getStream().isEnabled()) {
            LOGGER.warn("event=knowledge_index_stream_gateway_missing action=direct_fallback claimed={}", claimed.size());
        }
        processor.process(claimed);
    }

    private void relayToStream(List<KnowledgeIndexOutboxEvent> claimed) {
        for (KnowledgeIndexOutboxEvent event : claimed) {
            try {
                messageGateway.publish(event);
            } catch (RuntimeException failure) {
                processor.markForRetry(event, failure.getMessage());
            }
        }
    }
}
