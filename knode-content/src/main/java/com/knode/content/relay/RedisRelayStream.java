package com.knode.content.relay;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisRelayStream implements RelayStream {
    private final StringRedisTemplate redis;
    private final LettuceConnectionFactory factory;
    private final byte[] key;
    RedisRelayStream(StringRedisTemplate redis,LettuceConnectionFactory factory,String key) {
        this.redis=redis; this.factory=factory; this.key=key.getBytes(StandardCharsets.UTF_8);
    }
    @Override public boolean enabled() { return true; }
    @Override public String publish(String rawJson) {
        if(rawJson==null) throw new IllegalArgumentException("Missing event");
        byte[] bytes=rawJson.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>EventGuard.MAX_BYTES) throw new IllegalArgumentException("Event too large");
        return redis.execute((RedisCallback<String>) connection -> {
            var id=connection.streamCommands().xAdd(key,Map.of("event".getBytes(StandardCharsets.UTF_8),bytes));
            if(id==null) throw new IllegalStateException("Stream unavailable");
            return id.getValue();
        });
    }
    @Override public void close() { factory.destroy(); }
}
