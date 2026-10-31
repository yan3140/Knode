package com.knode.user.auth;

import com.knode.auth.SessionCodec;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisSessionAdapterTest {
    @Test @SuppressWarnings("unchecked") void usesDistinctClientsAtomicCreateTtlAndIdempotentRevoke() {
        StringRedisTemplate read=mock(StringRedisTemplate.class), write=mock(StringRedisTemplate.class);
        ValueOperations<String,String> readOps=mock(ValueOperations.class), writeOps=mock(ValueOperations.class);
        when(read.opsForValue()).thenReturn(readOps); when(write.opsForValue()).thenReturn(writeOps);
        Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
        var reader=new RedisSessionReader(read,"test"); var writer=new RedisSessionWriter(write,"test",clock);
        String sid=UUID.randomUUID().toString(), key="knode:test:v1:auth:session:"+sid;
        var session=new SessionStore.Session(1,sid,"42","blog","ACTIVE",clock.instant().getEpochSecond()+3600);
        when(writeOps.setIfAbsent(eq(key),anyString(),eq(Duration.ofSeconds(3600)))).thenReturn(true,false,null);
        writer.create(session);
        var json=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(writeOps).setIfAbsent(eq(key),json.capture(),eq(Duration.ofSeconds(3600)));
        assertEquals("42",SessionCodec.decode(json.getValue()).userId());
        assertFalse(json.getValue().contains("password"));
        when(readOps.get(key)).thenReturn(json.getValue(),null,"{bad");
        assertEquals(session,reader.find(sid).orElseThrow());
        assertTrue(reader.find(sid).isEmpty());
        assertThrows(SessionStore.Unavailable.class,() -> reader.find(sid));
        assertThrows(SessionStore.Unavailable.class,() -> writer.create(session));
        assertThrows(SessionStore.Unavailable.class,() -> writer.create(session));
        when(write.delete(key)).thenReturn(true,false,null);
        writer.revoke(sid); writer.revoke(sid);
        assertThrows(SessionStore.Unavailable.class,() -> writer.revoke(sid));
    }
    @Test @SuppressWarnings("unchecked") void redisExceptionsAreUnavailableAndNoCachedSuccess() {
        var redis=mock(StringRedisTemplate.class); ValueOperations<String,String> ops=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        String sid=UUID.randomUUID().toString(), key="knode:test:v1:auth:session:"+sid;
        when(ops.get(key)).thenThrow(new RuntimeException("offline"));
        var reader=new RedisSessionReader(redis,"test");
        assertThrows(SessionStore.Unavailable.class,() -> reader.find(sid));
        assertThrows(SessionStore.Unavailable.class,() -> reader.find(sid));
        verify(ops,times(2)).get(key);
        assertThrows(IllegalArgumentException.class,() -> new RedisSessionReader(redis,"bad:environment"));
    }

    @Test @SuppressWarnings("unchecked") void malformedRedisStateIs503ButValidWrongBindingIs401() throws Exception {
        var generator=java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
        var clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
        var memory=new UserTokensTest.Memory();
        var keys=java.util.Map.of("k",(java.security.interfaces.RSAPublicKey)pair.getPublic());
        var issuer=new UserTokens((java.security.interfaces.RSAPrivateKey)pair.getPrivate(),"k",keys,clock,memory,memory);
        var token=issuer.issue("42","blog"); String sid=issuer.authenticate(token,"blog").sid();
        var redis=mock(StringRedisTemplate.class); ValueOperations<String,String> ops=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        String key="knode:test:v1:auth:session:"+sid;
        String wrong=SessionCodec.encode(new com.knode.auth.Session(1,sid,"43","blog","ACTIVE",clock.instant().getEpochSecond()+3600));
        when(ops.get(key)).thenReturn("{bad",wrong);
        var verifier=new UserTokens(null,null,keys,clock,new RedisSessionReader(redis,"test"),null);
        assertEquals(503,assertThrows(AuthenticationFailure.class,() -> verifier.authenticate(token,"admin")).status());
        assertEquals(401,assertThrows(AuthenticationFailure.class,() -> verifier.authenticate(token,"admin")).status());
    }

    @Test @SuppressWarnings("unchecked") void writeProbeUsesExpiringIsolatedKeyAndFailsClosed() {
        var redis=mock(StringRedisTemplate.class);ValueOperations<String,String> ops=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(),eq("probe"),eq(Duration.ofSeconds(10)))).thenReturn(true,true,false);
        when(redis.delete(anyString())).thenReturn(true,false);
        var writer=new RedisSessionWriter(redis,"test",Clock.systemUTC());
        writer.probe();
        assertThrows(SessionStore.Unavailable.class,writer::probe);
        assertThrows(SessionStore.Unavailable.class,writer::probe);
        var key=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ops,times(3)).setIfAbsent(key.capture(),eq("probe"),eq(Duration.ofSeconds(10)));
        assertTrue(key.getAllValues().stream().allMatch(k -> k.startsWith("knode:test:v1:auth:session:probe-")));
        verify(redis,times(2)).delete(anyString());
    }
}
