package com.knode.content.counter;

import com.knode.content.auth.AuthConfiguration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CounterConfiguration {
    @Bean(destroyMethod="close")
    CounterStore counterStore(AuthConfiguration.Settings auth,
            @Value("${KNODE_CONTENT_COUNTER_ENABLED:false}") boolean enabled,
            @Value("${KNODE_CONTENT_COUNTER_WORKER_ENABLED:false}") boolean worker,
            @Value("${KNODE_CONTENT_COUNTER_REDIS_HOST:}") String host,
            @Value("${KNODE_CONTENT_COUNTER_REDIS_PORT:}") String portText,
            @Value("${KNODE_CONTENT_COUNTER_REDIS_USERNAME:}") String user,
            @Value("${KNODE_CONTENT_COUNTER_REDIS_PASSWORD:}") String password,
            @Value("${KNODE_AUTH_REDIS_USERNAME}") String authUser,
            @Value("${KNODE_AUTH_REDIS_PASSWORD}") String authPassword) {
        if (!enabled) {
            if (worker) throw new IllegalArgumentException("Counter worker requires enabled counter");
            return new CounterStore.Disabled();
        }
        int port;
        try { port=Integer.parseInt(portText); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid counter configuration"); }
        if (!numericHost(host) || port<1 || port>65535 || user.isBlank() || user.length()>128
                || password.isBlank() || password.length()>512 || user.equals(authUser)
                || password.equals(authPassword) || user.chars().anyMatch(Character::isISOControl)
                || password.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid counter configuration");
        var redis=new RedisStandaloneConfiguration(host,port);
        redis.setUsername(user);
        redis.setPassword(RedisPassword.of(password));
        var options=ClientOptions.builder().autoReconnect(false)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(400)).build())
                .sslOptions(SslOptions.builder().handshakeTimeout(Duration.ofMillis(500)).build()).build();
        var client=LettuceClientConfiguration.builder().useSsl().and().clientOptions(options)
                .commandTimeout(Duration.ofMillis(800)).build();
        var factory=new LettuceConnectionFactory(redis,client);
        factory.setShareNativeConnection(false);
        factory.afterPropertiesSet();
        factory.start();
        var template=new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return new RedisCounterStore(template,factory,"knode:"+auth.environment()+":v1:content:views:");
    }
    private static boolean numericHost(String host) {
        try { CounterIdentity.peer(host); return true; }
        catch(Exception failure) { return false; }
    }
}
