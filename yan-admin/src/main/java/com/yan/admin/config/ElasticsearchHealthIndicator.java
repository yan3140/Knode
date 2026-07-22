package com.yan.admin.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("elasticsearch")
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient client;

    public ElasticsearchHealthIndicator(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            if (client.ping().value()) {
                return Health.up().build();
            }
            return Health.down().withDetail("reason", "Elasticsearch ping returned false").build();
        } catch (Exception exception) {
            return Health.down().withException(exception).build();
        }
    }
}
