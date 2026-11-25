package com.knode.community.consumer;

import com.knode.community.auth.AuthConfiguration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import java.net.InetAddress;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class CommunityConsumerConfiguration {
    @Bean(destroyMethod = "close")
    public CommunityContentStream communityContentStream(
            AuthConfiguration.Settings auth,
            ContentEventIngestor ingestor,
            @Value("${KNODE_COMMUNITY_STREAM_ENABLED:false}") boolean enabled,
            @Value("${KNODE_COMMUNITY_STREAM_REDIS_HOST:}") String host,
            @Value("${KNODE_COMMUNITY_STREAM_REDIS_PORT:}") String portText,
            @Value("${KNODE_COMMUNITY_STREAM_REDIS_USERNAME:}") String user,
            @Value("${KNODE_COMMUNITY_STREAM_REDIS_PASSWORD:}") String password,
            @Value("${KNODE_COMMUNITY_STREAM_REDIS_TLS:true}") boolean tls,
            @Value("${KNODE_COMMUNITY_STREAM_BATCH:20}") int batchSize,
            @Value("${KNODE_COMMUNITY_STREAM_CLAIM_IDLE_MS:30000}") long claimIdleMs,
            @Value("${KNODE_AUTH_REDIS_USERNAME}") String authUser,
            @Value("${KNODE_AUTH_REDIS_PASSWORD}") String authPassword) {
        if (!enabled) {
            return new CommunityContentStream.Disabled();
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid community stream Redis port");
        }
        if (!tls
                || !numericIp(host)
                || port < 1 || port > 65535
                || user.isBlank() || user.length() > 128
                || password.isBlank() || password.length() > 512
                || user.equals(authUser)
                || password.equals(authPassword)
                || user.chars().anyMatch(Character::isISOControl)
                || password.chars().anyMatch(Character::isISOControl)
                || batchSize < 1 || batchSize > 100
                || claimIdleMs < 1000 || claimIdleMs > 600_000) {
            throw new IllegalArgumentException("Invalid community stream Redis configuration");
        }

        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration(host, port);
        redis.setUsername(user);
        redis.setPassword(RedisPassword.of(password));
        ClientOptions options = ClientOptions.builder()
                .autoReconnect(false)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(400)).build())
                .sslOptions(SslOptions.builder().handshakeTimeout(Duration.ofMillis(500)).build())
                .build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .useSsl().and()
                .clientOptions(options)
                .commandTimeout(Duration.ofMillis(800))
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis, client);
        factory.setShareNativeConnection(false);
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        String streamKey = "knode:" + auth.environment() + ":v1:events:content";
        return new RedisCommunityContentStream(template, factory, ingestor, streamKey, batchSize, claimIdleMs);
    }

    private static boolean numericIp(String host) {
        try {
            if (host == null || host.length() > 45) {
                return false;
            }
            if (host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                for (String part : host.split("\\.")) {
                    if (Integer.parseInt(part) > 255) {
                        return false;
                    }
                }
                return true;
            }
            return host.indexOf(':') >= 0 && host.matches("[0-9A-Fa-f:.]+") && InetAddress.getByName(host) != null;
        } catch (Exception invalid) {
            return false;
        }
    }
}
