package com.yan.admin.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElasticsearchHealthIndicatorTest {

    @Test
    void shouldReportUpWhenElasticsearchResponds() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        BooleanResponse ping = mock(BooleanResponse.class);
        when(client.ping()).thenReturn(ping);
        when(ping.value()).thenReturn(true);

        ElasticsearchHealthIndicator indicator = new ElasticsearchHealthIndicator(client);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
