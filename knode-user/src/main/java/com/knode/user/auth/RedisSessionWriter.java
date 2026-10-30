package com.knode.user.auth;

import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.auth.UserTokenVerifier;
import java.time.Clock;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** User service only; supply a separate write-authorized Redis connection. */
public final class RedisSessionWriter implements SessionStore.Writer {
    private final StringRedisTemplate redis;
    private final String prefix;
    private final Clock clock;
    public RedisSessionWriter(StringRedisTemplate redis, String environment, Clock clock) {
        this.redis = java.util.Objects.requireNonNull(redis);
        this.prefix = prefix(environment);
        this.clock = java.util.Objects.requireNonNull(clock);
    }
    static String prefix(String environment) {
        if (environment == null || !environment.matches("[a-z0-9-]{1,32}")) throw new IllegalArgumentException("environment");
        return "knode:" + environment + ":v1:auth:session:";
    }
    static String key(String prefix, String sid) {
        if (!UserTokenVerifier.validUuid(sid)) throw new IllegalArgumentException("sid");
        return prefix + sid;
    }
    @Override public void create(SessionStore.Session s) {
        String key = key(prefix,s.sid());
        long ttl = s.expiresAt() - clock.instant().getEpochSecond();
        if (s.schemaVersion() != 1 || !UserTokenVerifier.validUserId(s.userId())
                || !UserTokenVerifier.validScope(s.scope()) || !"ACTIVE".equals(s.status()) || ttl <= 0 || ttl > UserTokens.TTL)
            throw new IllegalArgumentException("Invalid session");
        try {
            String json = SessionCodec.encode(new Session(s.schemaVersion(),s.sid(),s.userId(),s.scope(),s.status(),s.expiresAt()));
            Boolean created = redis.opsForValue().setIfAbsent(key,json,Duration.ofSeconds(ttl));
            if (!Boolean.TRUE.equals(created)) throw new SessionStore.Unavailable("Session creation not confirmed");
        } catch (SessionStore.Unavailable e) { throw e; }
        catch (RuntimeException e) { throw new SessionStore.Unavailable("Authentication write unavailable",e); }
    }
    @Override public void revoke(String sid) {
        String key = key(prefix,sid);
        try {
            if (redis.delete(key) == null) throw new SessionStore.Unavailable("Session revocation not confirmed");
        } catch (SessionStore.Unavailable e) { throw e; }
        catch (RuntimeException e) { throw new SessionStore.Unavailable("Authentication revoke unavailable",e); }
    }
}
