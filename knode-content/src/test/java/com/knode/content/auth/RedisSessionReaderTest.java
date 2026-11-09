package com.knode.content.auth;

import com.knode.auth.AuthFailure;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisSessionReaderTest {
    @Test void onlyReadsAuthoritativeKeyAndKeepsMissingDistinct() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("knode:test:v1:auth:session:sid")).thenReturn(Mono.empty());
        var reader = new RedisSessionReader(redis, new AuthConfiguration.Settings("test"));
        assertTrue(reader.read("sid").isEmpty());
        verify(ops).get("knode:test:v1:auth:session:sid");
        verifyNoMoreInteractions(ops);
    }
    @Test void redisErrorIsUnavailable() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(Mono.error(new RuntimeException("sensitive")));
        var reader = new RedisSessionReader(redis, new AuthConfiguration.Settings("test"));
        AuthFailure error = assertThrows(AuthFailure.class, () -> reader.read("sid"));
        assertEquals(503, error.status());
        assertFalse(error.getMessage().contains("sensitive"));
    }
}
