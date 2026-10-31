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
        when(redis.delete("probe")).thenReturn(true);
        assertTrue(RedisSessionEventTransport.probe(redis,"probe"));
        assertFalse(RedisSessionEventTransport.probe(redis,"probe"));
        var order=inOrder(redis,ops);
        order.verify(redis).multi();
        order.verify(ops).add("probe",Map.of("probe","1"));
        order.verify(redis).expire("probe",Duration.ofSeconds(10));
        order.verify(redis).exec();
        verify(redis,atLeastOnce()).delete("probe");
        verify(redis,never()).delete("events");
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
