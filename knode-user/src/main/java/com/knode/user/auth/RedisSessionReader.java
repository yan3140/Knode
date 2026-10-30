package com.knode.user.auth;

import com.knode.auth.AuthFailure;
import com.knode.auth.SessionCodec;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Supply a dedicated read-only Redis connection; never caches a positive result. */
public final class RedisSessionReader implements SessionStore.Reader {
    private final StringRedisTemplate redis;
    private final String prefix;
    public RedisSessionReader(StringRedisTemplate redis, String environment) {
        this.redis = java.util.Objects.requireNonNull(redis);
        this.prefix = RedisSessionWriter.prefix(environment);
    }
    @Override public Optional<SessionStore.Session> find(String sid) {
        String key = RedisSessionWriter.key(prefix,sid);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return Optional.empty();
            var s = SessionCodec.decode(json);
            return Optional.of(new SessionStore.Session(s.schemaVersion(),s.sid(),s.userId(),s.scope(),s.status(),s.expiresAt()));
        } catch (AuthFailure e) { throw new SessionStore.Unavailable("Unreadable authentication record",e); }
        catch (RuntimeException e) { throw new SessionStore.Unavailable("Authentication read unavailable",e); }
    }
}
