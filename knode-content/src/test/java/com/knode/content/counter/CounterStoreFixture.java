package com.knode.content.counter;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import java.time.Duration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// Loopback fakeredis only. Production configuration always requires TLS.
public final class CounterStoreFixture implements AutoCloseable {
    public final CounterStore store;
    public final StringRedisTemplate redis;
    public final String prefix;
    private final LettuceConnectionFactory factory;
    private CounterStoreFixture(CounterStore store,StringRedisTemplate redis,String prefix,LettuceConnectionFactory factory) {
        this.store=store; this.redis=redis; this.prefix=prefix; this.factory=factory;
    }
    public static CounterStoreFixture open(int port,String prefix) {
        var config=new RedisStandaloneConfiguration("127.0.0.1",port);
        var options=ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).autoReconnect(false).build();
        var client=LettuceClientConfiguration.builder().clientOptions(options).commandTimeout(Duration.ofSeconds(2)).build();
        var factory=new LettuceConnectionFactory(config,client);
        factory.setShareNativeConnection(false); factory.afterPropertiesSet(); factory.start();
        var redis=new StringRedisTemplate(factory); redis.afterPropertiesSet();
        return new CounterStoreFixture(new RedisCounterStore(redis,factory,prefix),redis,prefix,factory);
    }
    @Override public void close() { factory.destroy(); }
}
