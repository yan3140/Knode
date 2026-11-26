package com.knode.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.knode.search.ops.SearchOpsProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({
        SearchElasticsearchProperties.class,
        SearchQueryProperties.class,
        SearchCacheProperties.class,
        SearchClientsProperties.class,
        SearchOpsProperties.class
})
public class SearchRuntimeConfig {

    @Bean
    public Clock searchClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchClient elasticsearchClient(
            SearchElasticsearchProperties properties,
            SearchQueryProperties queryProperties) {
        if (!StringUtils.hasText(properties.getUrl()) || !StringUtils.hasText(properties.getIndex())) {
            throw new IllegalStateException("knode.search.elasticsearch.url and index must be configured");
        }
        URI uri = URI.create(properties.getUrl().trim());
        long totalMs = Math.max(1L, queryProperties.getTotalTimeoutMs());
        long connectMs = Math.max(1L, Math.min(properties.getConnectTimeoutMs(), totalMs));
        long connReqMs = Math.max(1L, Math.min(properties.getConnectionRequestTimeoutMs(), totalMs));
        long responseMs = Math.max(1L, Math.min(properties.getResponseTimeoutMs(), totalMs));
        Rest5Client restClient = Rest5Client.builder(uri)
                .setConnectionConfigCallback(builder -> builder
                        .setConnectTimeout(Timeout.ofMilliseconds(connectMs)))
                .setRequestConfigCallback(builder -> builder
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connReqMs))
                        .setResponseTimeout(Timeout.ofMilliseconds(responseMs)))
                .build();
        ObjectMapper esObjectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new ElasticsearchClient(
                new Rest5ClientTransport(restClient, new JacksonJsonpMapper(esObjectMapper)));
    }

    @Bean(name = "searchOutboundHttpClient")
    public HttpClient searchOutboundHttpClient(SearchClientsProperties properties) {
        long connectMs = Math.max(1L, properties.getConnectTimeoutMs());
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null))
                .build();
    }

    @Bean(name = "searchCacheRedisConnectionFactory")
    @ConditionalOnProperty(prefix = "knode.search.cache", name = "enabled", havingValue = "true")
    public LettuceConnectionFactory searchCacheRedisConnectionFactory(SearchCacheProperties properties) {
        SearchCacheProperties.Redis redis = properties.getRedis();
        if (!StringUtils.hasText(redis.getHost()) || redis.getPort() < 1 || redis.getPort() > 65535) {
            throw new IllegalStateException("Invalid knode.search.cache.redis host/port configuration");
        }
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(redis.getHost().trim(), redis.getPort());
        standalone.setDatabase(Math.max(0, redis.getDatabase()));
        if (StringUtils.hasText(redis.getUsername())) {
            standalone.setUsername(redis.getUsername());
        }
        if (StringUtils.hasText(redis.getPassword())) {
            standalone.setPassword(RedisPassword.of(redis.getPassword()));
        }

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(Math.max(1L, redis.getConnectTimeoutMs())))
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();
        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientBuilder = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofMillis(Math.max(1L, redis.getCommandTimeoutMs())));
        if (redis.isTls()) {
            clientBuilder.useSsl();
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, clientBuilder.build());
        factory.setValidateConnection(false);
        return factory;
    }

    @Bean(name = "searchCacheRedisTemplate")
    @ConditionalOnProperty(prefix = "knode.search.cache", name = "enabled", havingValue = "true")
    public StringRedisTemplate searchCacheRedisTemplate(
            @Qualifier("searchCacheRedisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
