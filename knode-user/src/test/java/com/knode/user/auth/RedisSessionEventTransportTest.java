package com.knode.user.auth;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisSessionEventTransportTest {
    @Test @SuppressWarnings("unchecked") void appendsOnlyEventFieldAndProbeUsesDistinctExpiringKey() {
        var redis=mock(StringRedisTemplate.class);
        StreamOperations<String,Object,Object> ops=mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(ops);
        when(ops.add(eq("events"),anyMap())).thenReturn(RecordId.of("1-0"),(RecordId)null);
        RedisSessionEventTransport.append(redis,"events","immutable");
        verify(ops).add("events",Map.of("event","immutable"));
        assertThrows(IllegalStateException.class,()->RedisSessionEventTransport.append(redis,"events","immutable"));
        when(redis.execute(org.mockito.ArgumentMatchers.<SessionCallback<List<Object>>>any()))
                .thenAnswer(inv -> ((SessionCallback<List<Object>>)inv.getArgument(0)).execute(redis));
        when(redis.exec()).thenReturn(List.of(RecordId.of("2-0"),true),List.of(RecordId.of("3-0"),false));
        when(redis.delete(anyString())).thenReturn(true);
        assertTrue(RedisSessionEventTransport.probe(redis,"probe"));
        assertFalse(RedisSessionEventTransport.probe(redis,"probe"));
        var key=org.mockito.ArgumentCaptor.forClass(String.class);
        var order=inOrder(redis,ops);
        order.verify(redis).multi();
        order.verify(ops).add(key.capture(),eq(Map.of("probe","1")));
        assertTrue(key.getValue().matches("probe:[0-9a-f-]{36}"));
        order.verify(redis).expire(key.getValue(),Duration.ofSeconds(10));
        order.verify(redis).exec();
        verify(redis,atLeastOnce()).delete(key.getValue());
        verify(redis,never()).delete("events");
    }
    @Test @SuppressWarnings("unchecked") void overlappingProbesDeleteOnlyTheirOwnUniqueKeys() throws Exception {
        var redis=mock(StringRedisTemplate.class);
        StreamOperations<String,Object,Object> ops=mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(ops);
        when(redis.execute(org.mockito.ArgumentMatchers.<SessionCallback<List<Object>>>any()))
                .thenAnswer(inv -> ((SessionCallback<List<Object>>)inv.getArgument(0)).execute(redis));
        var created=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var allKeys=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var bothCreated=new java.util.concurrent.CountDownLatch(2);
        when(ops.add(anyString(),anyMap())).thenAnswer(inv -> {
            String key=inv.getArgument(0);created.add(key);allKeys.add(key);bothCreated.countDown();
            assertTrue(bothCreated.await(10,java.util.concurrent.TimeUnit.SECONDS));
            return null;
        });
        when(redis.exec()).thenReturn(List.of(RecordId.of("1-0"),true));
        when(redis.delete(anyString())).thenAnswer(inv -> created.remove((String)inv.getArgument(0)));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(() -> RedisSessionEventTransport.probe(redis,"knode:test:v1:events:probe"));
            var second=pool.submit(() -> RedisSessionEventTransport.probe(redis,"knode:test:v1:events:probe"));
            assertTrue(first.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(second.get(15,java.util.concurrent.TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals(2,allKeys.size());assertTrue(created.isEmpty());
        for (String key:allKeys) {
            assertTrue(key.startsWith("knode:test:v1:events:probe:"));
            verify(redis).expire(key,Duration.ofSeconds(10));
            verify(redis,atLeastOnce()).delete(key);
        }
    }

    @Test void rejectsOverlappingProbeNamespaceAndOverlongPrefix() {
        var env=new MockEnvironment().withProperty("KNODE_AUTH_ENVIRONMENT","test")
                .withProperty("KNODE_SESSION_EVENTS_REDIS_USERNAME","events")
                .withProperty("KNODE_SESSION_EVENTS_STREAM_KEY","knode:test:v1:events:probe:actual")
                .withProperty("KNODE_SESSION_EVENTS_PROBE_PREFIX","knode:test:v1:events:probe");
        assertThrows(IllegalArgumentException.class,()->new RedisSessionEventTransport(env));
        env.setProperty("KNODE_SESSION_EVENTS_STREAM_KEY","knode:test:v1:events:session");
        env.setProperty("KNODE_SESSION_EVENTS_PROBE_PREFIX","x".repeat(219));
        assertThrows(IllegalArgumentException.class,()->new RedisSessionEventTransport(env));
        env.setProperty("KNODE_SESSION_EVENTS_PROBE_PREFIX","knode:test:v1:auth");
        assertThrows(IllegalArgumentException.class,()->new RedisSessionEventTransport(env));
    }

    @Test void refusesAuthNamespaceForStreamOrProbeBeforeConnecting() {
        var env=new MockEnvironment().withProperty("KNODE_AUTH_ENVIRONMENT","test")
                .withProperty("KNODE_SESSION_EVENTS_REDIS_USERNAME","events")
                .withProperty("KNODE_SESSION_EVENTS_STREAM_KEY","knode:test:v1:auth:session:bad")
                .withProperty("KNODE_SESSION_EVENTS_PROBE_KEY","knode:test:v1:events:probe");
        assertThrows(IllegalArgumentException.class,()->new RedisSessionEventTransport(env));
    }
    private static MockEnvironment configuration(String environment,String stream,String probe) {
        var env=new MockEnvironment().withProperty("KNODE_AUTH_ENVIRONMENT",environment)
                .withProperty("KNODE_SESSION_EVENTS_REDIS_USERNAME","events-only")
                .withProperty("KNODE_SESSION_EVENTS_REDIS_HOST","127.0.0.1")
                .withProperty("KNODE_SESSION_EVENTS_REDIS_PORT","6379")
                .withProperty("KNODE_SESSION_EVENTS_REDIS_PASSWORD","synthetic-test-only")
                .withProperty("KNODE_SESSION_EVENTS_REDIS_TLS","false")
                .withProperty("KNODE_SESSION_EVENTS_PROBE_PREFIX",probe);
        if (stream!=null) env.setProperty("KNODE_SESSION_EVENTS_STREAM_KEY",stream);
        return env;
    }

    @Test void rejectsForeignLegacyAndProductionBusinessKeysBeforeConnectionFactoryExists() {
        String events="knode:prod:v1:events:";
        String testEvents="knode:verify-test:v1:events:";
        String[][] bad={
                {"prod","knode:other:v1:events:session",events+"probe"},
                {"prod",events+"session","knode:other:v1:events:probe"},
                {"prod","login:blog:42",events+"probe"},
                {"prod",events+"session","blog:legacy:probe"},
                {"prod","knode:prod:v1:business:stream",events+"probe"},
                {"prod",events+"session","knode:prod:v1:business:probe"},
                {"prod","knode:prod:v1:events-extra:stream",events+"probe"},
                {"prod",events,events+"probe"},
                {"prod",events+"session",events},
                {"verify-test","knode:verify-other:v1:business:stream",testEvents+"probe"},
                {"verify-test",testEvents+"session","knode:verify-other:v1:business:probe"},
                {"verify-test","knode:prod:v1:events:session",testEvents+"probe"},
                {"verify-","knode:verify-:v1:business:stream","knode:verify-:v1:business:probe"}
        };
        try (var factories=mockConstruction(LettuceConnectionFactory.class)) {
            for (String[] item:bad) {
                var failure=assertThrows(IllegalArgumentException.class,() ->
                        new RedisSessionEventTransport(configuration(item[0],item[1],item[2])));
                assertEquals("Distinct stream and probe prefixes within the current environment are required",failure.getMessage());
            }
            assertTrue(factories.constructed().isEmpty(),"invalid keys must be rejected before a connection factory is created");
        }
    }

    @Test void allowsCurrentEnvironmentEventsAndVerifyOnlyBusinessOverridesWithoutNetwork() {
        String[][] accepted={
                {"prod",null,"knode:prod:v1:events:probe"},
                {"prod","knode:prod:v1:events:session","knode:prod:v1:events:probe"},
                {"verify-test","knode:verify-test:v1:events:session","knode:verify-test:v1:events:probe"},
                {"verify-test","knode:verify-test:v1:business:relay","knode:verify-test:v1:business:probe"},
                {"verify-test","knode:verify-test:v1:events:session","knode:verify-test:v1:business:probe"}
        };
        try (var factories=mockConstruction(LettuceConnectionFactory.class)) {
            for (String[] item:accepted) {
                var transport=assertDoesNotThrow(() -> new RedisSessionEventTransport(configuration(item[0],item[1],item[2])));
                assertTrue(transport.enabled());transport.close();
            }
            assertEquals(accepted.length,factories.constructed().size());
            for (var factory:factories.constructed()) {
                verify(factory).start();verify(factory).destroy();verify(factory,never()).getConnection();
            }
        }
    }

    @Test @SuppressWarnings("unchecked") void defaultStreamRemainsCurrentEnvironmentSessionStream() {
        StreamOperations<String,Object,Object> ops=mock(StreamOperations.class);
        when(ops.add(eq("knode:prod:v1:events:session"),anyMap())).thenReturn(RecordId.of("1-0"));
        try (var factories=mockConstruction(LettuceConnectionFactory.class);
             var templates=mockConstruction(StringRedisTemplate.class,(mock,context) -> when(mock.opsForStream()).thenReturn(ops))) {
            var transport=new RedisSessionEventTransport(configuration("prod",null,"knode:prod:v1:events:probe"));
            try {
                transport.publish("synthetic-envelope");
                verify(ops).add("knode:prod:v1:events:session",Map.of("event","synthetic-envelope"));
                verify(factories.constructed().get(0),never()).getConnection();
            } finally {transport.close();}
        }
    }

    @Test void refusesUsingSessionWriterIdentityBeforeConnecting() {
        var env=new MockEnvironment().withProperty("KNODE_AUTH_ENVIRONMENT","test")
                .withProperty("KNODE_SESSION_EVENTS_REDIS_USERNAME","shared")
                .withProperty("KNODE_AUTH_REDIS_WRITE_USERNAME","shared");
        assertThrows(IllegalArgumentException.class,()->new RedisSessionEventTransport(env));
    }
}
