package com.yan.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.MDC;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties({ElasticsearchProperties.class, SearchSyncProperties.class})
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public ElasticsearchClient elasticsearchClient(
            ElasticsearchProperties properties,
            @Qualifier("elasticsearchObjectMapper") ObjectMapper objectMapper) {
        URI uri = URI.create(properties.getUrl());
        Rest5Client restClient = Rest5Client.builder(uri)
                .setConnectionConfigCallback(builder -> builder
                        .setConnectTimeout(Timeout.ofMilliseconds(1_000)))
                .setRequestConfigCallback(builder -> builder
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(1_000))
                        .setResponseTimeout(Timeout.ofMilliseconds(5_000)))
                .build();
        return new ElasticsearchClient(
                new Rest5ClientTransport(restClient, new JacksonJsonpMapper(objectMapper)));
    }

    @Bean
    @Qualifier("elasticsearchObjectMapper")
    public ObjectMapper elasticsearchObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean(name = "searchSyncExecutor")
    public ThreadPoolTaskExecutor searchSyncExecutor(SearchSyncProperties properties) {
        if (properties.getCorePoolSize() < 1
                || properties.getMaxPoolSize() < properties.getCorePoolSize()
                || properties.getQueueCapacity() < 1
                || properties.getKeepAliveSeconds() < 1) {
            throw new IllegalStateException("Invalid blog.search.sync thread pool configuration");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setAllowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setThreadNamePrefix("search-sync-");
        executor.setTaskDecorator(this::withMdcContext);
        executor.initialize();
        return executor;
    }

    private Runnable withMdcContext(Runnable task) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            try {
                if (callerContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(callerContext);
                }
                task.run();
            } finally {
                if (previousContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousContext);
                }
            }
        };
    }
}
