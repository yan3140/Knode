package com.yan.cache;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yan.config.RedisConfig;
import com.yan.constants.CacheConstants;
import com.yan.mapper.ArticleMapper;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.mapper.CacheInvalidationOutboxMapper;
import com.yan.search.outbox.ArticleSearchOutboxProcessor;
import com.yan.search.outbox.ArticleSearchOutboxService;
import com.yan.service.impl.ArticleCounterServiceImpl;
import com.yan.utils.CacheClient;
import com.yan.utils.RedisCache;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

@EnabledIfSystemProperty(named = "knode.infra.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheConsistencyIntegrationTest {
    private final String namespace = "knode:risk-test:" + UUID.randomUUID() + ":";
    private final String database = "knode_risk_test_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private LettuceConnectionFactory redisConnectionFactory;
    private StringRedisTemplate strings;
    private RedisCache redis;
    private AnnotationConfigApplicationContext context;
    private TransactionTemplate transactions;
    private CacheInvalidationOutboxMapper invalidations;
    private CacheInvalidationOutboxService recorder;
    private ArticleMapper articles;
    private ArticleSearchOutboxService search;
    private CacheClient caches;

    @BeforeAll
    void connectIsolatedFixtures() throws Exception {
        String mysqlUrl = System.getenv().getOrDefault("KNODE_TEST_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/");
        String user = System.getenv().getOrDefault("KNODE_TEST_MYSQL_USER", "root");
        String password = System.getenv("KNODE_TEST_MYSQL_PASSWORD");
        adminJdbc = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl, user, password));
        adminJdbc.execute("CREATE DATABASE " + database);
        dataSource = new DriverManagerDataSource(mysqlUrl + database, user, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE yan_article LIKE yan_blog.yan_article");
        new ResourceDatabasePopulator(
                new FileSystemResource("../docs/sql/V20260719__article_search_outbox.sql"),
                new FileSystemResource("../docs/sql/V20260727__cache_invalidation_outbox.sql"),
                new FileSystemResource("../docs/sql/V20261016__cache_invalidation_rerun.sql"))
                .execute(dataSource);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/ArticleMapper.xml"),
                new ClassPathResource("mapper/ArticleSearchOutboxMapper.xml"),
                new ClassPathResource("mapper/CacheInvalidationOutboxMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        SqlSessionTemplate sessions = new SqlSessionTemplate(sqlSessionFactory);
        invalidations = sessions.getMapper(CacheInvalidationOutboxMapper.class);
        articles = sessions.getMapper(ArticleMapper.class);
        search = new ArticleSearchOutboxService(sessions.getMapper(ArticleSearchOutboxMapper.class),
                mock(ArticleSearchOutboxProcessor.class), List.of(), 30, 100, 10, 300, 8, false);

        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("KNODE_TEST_REDIS_HOST", "192.168.25.129"),
                Integer.parseInt(System.getenv().getOrDefault("KNODE_TEST_REDIS_PORT", "6379")));
        redisConfiguration.setPassword(RedisPassword.of(System.getenv().getOrDefault("KNODE_TEST_REDIS_PASSWORD", "")));
        redisConnectionFactory = new LettuceConnectionFactory(redisConfiguration);
        redisConnectionFactory.afterPropertiesSet();
        strings = new StringRedisTemplate(redisConnectionFactory);
        RedisSerializer<Object> keys = new RedisSerializer<>() {
            @Override
            public byte[] serialize(Object key) {
                return (namespace + key).getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Object deserialize(byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8).substring(namespace.length());
            }
        };
        strings.setKeySerializer(keys);
        redis = new RedisCache();
        redis.redisTemplate = new RedisConfig().redisTemplate(redisConnectionFactory);
        redis.redisTemplate.setKeySerializer(keys);

        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(CacheInvalidationOutboxService.class, () -> new CacheInvalidationOutboxService(
                invalidations, redis, 50, 30, 10, 300, 8));
        context.refresh();
        recorder = context.getBean(CacheInvalidationOutboxService.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        caches = new CacheClient(redis, context.getBeanProvider(CacheInvalidationRecorder.class));
        assertThat(strings.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM yan_article");
        jdbc.update("DELETE FROM yan_article_search_outbox");
        jdbc.update("DELETE FROM yan_cache_invalidation_outbox");
        jdbc.update("INSERT INTO yan_article(id,title,status,view_count,del_flag) VALUES(1,'before','0',10,0)");
        clearRedisFixtures();
    }

    @AfterAll
    void cleanUp() {
        if (strings != null) {
            clearRedisFixtures();
        }
        if (context != null) {
            context.close();
        }
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
        if (adminJdbc != null) {
            adminJdbc.execute("DROP DATABASE IF EXISTS " + database);
        }
    }

    @Test
    void expiredOwnerCannotUnlockNewOwnersLockWithProductionSerializer() {
        redis.redisTemplate.opsForValue().set("lock:test", "owner-a", Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(3)).until(() -> !strings.hasKey("lock:test"));
        assertThat(redis.setCacheObjectIfAbsent("lock:test", "owner-b", 10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(redis.deleteObjectIfValueMatches("lock:test", "owner-a")).isFalse();
        assertThat(redis.<String>getCacheObject("lock:test")).isEqualTo("owner-b");
        assertThat(redis.deleteObjectIfValueMatches("lock:test", "owner-b")).isTrue();
        assertThat(strings.hasKey("lock:test")).isFalse();
    }

    @Test
    void rollbackPreservesCacheAndRollsBackBusinessWriteAndInvalidationTogether() {
        redis.setCacheObject("article:1", "before");
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE yan_article SET title='after' WHERE id=1");
            caches.deleteAfterCommit("article:1");
            assertThat(invalidationCount()).isEqualTo(1);
            assertThat(new JdbcTemplate(dataSource).queryForObject(
                    "SELECT title FROM yan_article WHERE id=1", String.class)).isEqualTo("after");
            assertThat(redis.<String>getCacheObject("article:1")).isEqualTo("before");
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT title FROM yan_article WHERE id=1", String.class)).isEqualTo("before");
        assertThat(invalidationCount()).isZero();
        assertThat(redis.<String>getCacheObject("article:1")).isEqualTo("before");
    }

    @Test
    void durableTaskRecoversWhenAfterCommitEvictionNeverRuns() {
        redis.setCacheObject("article:1", "before");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE yan_article SET title='after' WHERE id=1");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw new IllegalStateException("simulate interruption before eviction callback");
                }
            });
            caches.deleteAfterCommit("article:1");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT title FROM yan_article WHERE id=1", String.class)).isEqualTo("after");
        assertThat(invalidationCount()).isEqualTo(1);
        assertThat(redis.<String>getCacheObject("article:1")).isEqualTo("before");
        recorder.retryPending();
        assertThat(strings.hasKey("article:1")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM yan_cache_invalidation_outbox", String.class)).isEqualTo("SUCCEEDED");
    }

    @Test
    void afterCommitFailureRecordIsCommittedInIndependentTransaction() {
        RedisCache unavailable = mock(RedisCache.class);
        doThrow(new IllegalStateException("redis unavailable")).when(unavailable).deleteObject("article:1");
        CacheClient fallback = new CacheClient(unavailable, context.getBeanProvider(CacheInvalidationRecorder.class));
        transactions.executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        fallback.delete("article:1");
                    }
                }));

        assertThat(invalidationCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM yan_cache_invalidation_outbox", String.class))
                .isEqualTo("redis unavailable");
    }

    @Test
    void invalidationArrivingDuringProcessingIsNotLost() {
        recorder.record("article:1", false, "first");
        Long id = jdbc.queryForObject("SELECT id FROM yan_cache_invalidation_outbox", Long.class);
        assertThat(invalidations.claim(id, 30)).isEqualTo(1);
        recorder.record("article:1", false, "second");
        assertThat(invalidations.markSucceeded(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM yan_cache_invalidation_outbox", String.class)).isEqualTo("PENDING");
        recorder.retryPending();
        assertThat(jdbc.queryForObject("SELECT status FROM yan_cache_invalidation_outbox", String.class)).isEqualTo("SUCCEEDED");
    }

    @Test
    void searchOutboxFailureRollsBackCounterAndOutboxButPreservesDirtyInRealStores() {
        dirtyCounter(15);
        ArticleSearchOutboxService failingSearch = spy(search);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("outbox failure after SQL insert");
        }).when(failingSearch).enqueue(1L);

        assertThatThrownBy(() -> counter(strings, failingSearch).flushDirtyCounters(10))
                .isInstanceOf(IllegalStateException.class);

        assertThat(databaseCount()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yan_article_search_outbox", Integer.class)).isZero();
        assertThat(strings.opsForSet().isMember(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, "1")).isTrue();
    }

    @Test
    void counterCommitsBeforeCleanupAndNewVisitsRemainDirty() {
        dirtyCounter(15);
        transactions.executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    strings.opsForHash().increment(CacheConstants.ARTICLE_VIEW_COUNT, "1", 1L);
                }
            });
            counter(strings, search).flushDirtyCounters(10);
            assertThat(strings.opsForSet().isMember(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, "1")).isTrue();
        });

        assertThat(databaseCount()).isEqualTo(15);
        assertThat(strings.opsForSet().isMember(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, "1")).isTrue();
        counter(strings, search).flushDirtyCounters(10);
        assertThat(databaseCount()).isEqualTo(16);
        assertThat(strings.opsForSet().isMember(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, "1")).isFalse();
    }

    @Test
    void redisCleanupFailureDoesNotUndoCommittedCounterOrDiscardDirty() {
        dirtyCounter(15);
        StringRedisTemplate failingRedis = spy(strings);
        doThrow(new IllegalStateException("redis unavailable after commit"))
                .when(failingRedis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(counter(failingRedis, search).flushDirtyCounters(10)).isEqualTo(1);

        assertThat(databaseCount()).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yan_article_search_outbox", Integer.class)).isEqualTo(1);
        assertThat(strings.opsForSet().isMember(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, "1")).isTrue();
        counter(strings, search).flushDirtyCounters(10);
        assertThat(strings.opsForSet().isMember(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, "1")).isFalse();
    }

    private ArticleCounterServiceImpl counter(StringRedisTemplate template, ArticleSearchOutboxService outbox) {
        return new ArticleCounterServiceImpl(template, articles, outbox, context.getBean(PlatformTransactionManager.class));
    }

    private int invalidationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM yan_cache_invalidation_outbox", Integer.class);
    }

    private long databaseCount() {
        return jdbc.queryForObject("SELECT view_count FROM yan_article WHERE id=1", Long.class);
    }

    private void dirtyCounter(long value) {
        strings.opsForHash().put(CacheConstants.ARTICLE_VIEW_COUNT, "1", Long.toString(value));
        strings.opsForSet().add(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, "1");
    }

    private void clearRedisFixtures() {
        var keys = strings.keys("*");
        if (keys != null && !keys.isEmpty()) {
            strings.delete(keys);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }
}
