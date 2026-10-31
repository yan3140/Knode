package com.knode.user.auth;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
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
    @Test void refusesUsingSessionWriterIdentityBeforeConnecting() {
        var env=new MockEnvironment().withProperty("KNODE_AUTH_ENVIRONMENT","test")
                .withProperty("KNODE_SESSION_EVENTS_REDIS_USERNAME","shared")
                .withProperty("KNODE_AUTH_REDIS_WRITE_USERNAME","shared");
        assertThrows(IllegalArgumentException.class,()->new RedisSessionEventTransport(env));
    }
}
