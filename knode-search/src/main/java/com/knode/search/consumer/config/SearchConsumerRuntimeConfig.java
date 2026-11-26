package com.knode.search.consumer.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.Locale;

@Configuration
@EnableConfigurationProperties(SearchConsumerProperties.class)
public class SearchConsumerRuntimeConfig {

    private final SearchConsumerProperties properties;

    public SearchConsumerRuntimeConfig(SearchConsumerProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validateConfiguration() {
        SearchConsumerProperties.StreamProps stream = properties.getStream();
        SearchConsumerProperties.IndexerProps indexer = properties.getIndexer();
        if (!properties.isEnabled()) {
            if (stream.isEnabled() || stream.isWorkerEnabled() || indexer.isEnabled() || indexer.isWorkerEnabled()) {
                throw new IllegalStateException(
                        "knode.search.consumer.stream and indexer cannot be enabled while knode.search.consumer.enabled is false");
            }
            return;
        }
        if (stream.isWorkerEnabled() && !stream.isEnabled()) {
            throw new IllegalStateException("knode.search.consumer.stream.worker-enabled requires stream.enabled=true");
        }
        if (indexer.isWorkerEnabled() && !indexer.isEnabled()) {
            throw new IllegalStateException("knode.search.consumer.indexer.worker-enabled requires indexer.enabled=true");
        }
        SearchConsumerProperties.DataSourceProps ds = properties.getDatasource();
        if (!StringUtils.hasText(ds.getUrl())) {
            throw new IllegalStateException("knode.search.consumer.datasource.url must be configured when consumer is enabled");
        }
        if (ds.getTransactionTimeoutSeconds() < 1 || ds.getTransactionTimeoutSeconds() > 60) {
            throw new IllegalStateException("knode.search.consumer.datasource.transaction-timeout-seconds must be in 1..60");
        }
        if (stream.isEnabled()) {
            if (!StringUtils.hasText(stream.getGroup())) {
                throw new IllegalStateException("knode.search.consumer.stream.group must be non-blank");
            }
            if (stream.getBatchSize() < 1 || stream.getBatchSize() > 100) {
                throw new IllegalStateException("knode.search.consumer.stream.batch-size must be in 1..100");
            }
            if (stream.getBlockTimeoutMs() < 10L || stream.getBlockTimeoutMs() > 5000L) {
                throw new IllegalStateException("knode.search.consumer.stream.block-timeout-ms must be in 10..5000");
            }
            if (stream.getMinIdleMs() < 10L) {
                throw new IllegalStateException("knode.search.consumer.stream.min-idle-ms must be >= 10");
            }
        }
        if (indexer.isEnabled()) {
            if (indexer.getBatchSize() < 1 || indexer.getBatchSize() > 100) {
                throw new IllegalStateException("knode.search.consumer.indexer.batch-size must be in 1..100");
            }
            if (indexer.getLeaseSeconds() < 1 || indexer.getLeaseSeconds() > 300) {
                throw new IllegalStateException("knode.search.consumer.indexer.lease-seconds must be in 1..300");
            }
            if (indexer.getMaxAttempts() < 1 || indexer.getMaxAttempts() > 20) {
                throw new IllegalStateException("knode.search.consumer.indexer.max-attempts must be in 1..20");
            }
            String refresh = indexer.getRefreshPolicy() == null
                    ? ""
                    : indexer.getRefreshPolicy().trim().toLowerCase(Locale.ROOT);
            if (!"wait_for".equals(refresh) && !"true".equals(refresh)) {
                throw new IllegalStateException("knode.search.consumer.indexer.refresh-policy must be wait_for or true");
            }
        }
    }

    @Bean(name = "searchConsumerDataSource", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
    public HikariDataSource searchConsumerDataSource() {
        SearchConsumerProperties.DataSourceProps ds = properties.getDatasource();
        HikariConfig config = new HikariConfig();
        config.setPoolName("knode-search-consumer-pool");
        config.setJdbcUrl(ds.getUrl().trim());
        if (StringUtils.hasText(ds.getDriverClassName())) {
            config.setDriverClassName(ds.getDriverClassName().trim());
        }
        if (ds.getUsername() != null) {
            config.setUsername(ds.getUsername());
        }
        if (ds.getPassword() != null) {
            config.setPassword(ds.getPassword());
        }
        config.setMaximumPoolSize(Math.max(1, Math.min(50, ds.getMaximumPoolSize())));
        config.setConnectionTimeout(Math.max(100L, ds.getConnectionTimeoutMs()));
        config.setAutoCommit(true);
        return new HikariDataSource(config);
    }

    @Bean(name = "searchConsumerJdbcTemplate")
    @ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
    public JdbcTemplate searchConsumerJdbcTemplate(@Qualifier("searchConsumerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "searchConsumerTransactionManager")
    @ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
    public PlatformTransactionManager searchConsumerTransactionManager(
            @Qualifier("searchConsumerDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "searchConsumerTransactionTemplate")
    @ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
    public TransactionTemplate searchConsumerTransactionTemplate(
            @Qualifier("searchConsumerTransactionManager") PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(properties.getDatasource().getTransactionTimeoutSeconds());
        return template;
    }

    public static String sanitizeEnvironment(String rawEnvironment) {
        if (rawEnvironment == null || rawEnvironment.isBlank()) {
            return "local";
        }
        String normalized = rawEnvironment.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-");
        return normalized.isEmpty() ? "local" : normalized;
    }
}
