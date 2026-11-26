package com.knode.search.auth;

import com.knode.auth.AuthFailure;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.search.consumer.config.SearchConsumerRuntimeConfig;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;

public class LettuceAuthSessionStore implements AuthSessionStore, AutoCloseable {
    private final String environment;
    private final RedisURI redisUri;
    private final RedisClient redisClient;
    private final Object connectionLock = new Object();
    private StatefulRedisConnection<String, String> connection;

    public LettuceAuthSessionStore(SearchAuthProperties.RedisProps redisProps, String rawEnvironment) {
        if (redisProps == null || !StringUtils.hasText(redisProps.getHost())
                || redisProps.getPort() < 1 || redisProps.getPort() > 65535) {
            throw new IllegalStateException("Invalid knode.search.auth.session.redis host/port configuration");
        }
        this.environment = SearchConsumerRuntimeConfig.sanitizeEnvironment(rawEnvironment);

        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(redisProps.getHost().trim())
                .withPort(redisProps.getPort())
                .withDatabase(Math.max(0, redisProps.getDatabase()))
                .withTimeout(Duration.ofMillis(Math.max(100L, redisProps.getCommandTimeoutMs())));
        if (StringUtils.hasText(redisProps.getUsername()) && StringUtils.hasText(redisProps.getPassword())) {
            uriBuilder.withAuthentication(redisProps.getUsername(), redisProps.getPassword().toCharArray());
        } else if (StringUtils.hasText(redisProps.getPassword())) {
            uriBuilder.withPassword(redisProps.getPassword().toCharArray());
        }
        if (redisProps.isTls()) {
            uriBuilder.withSsl(true);
        }
        this.redisUri = uriBuilder.build();

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(Math.max(100L, redisProps.getConnectTimeoutMs())))
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();
        this.redisClient = RedisClient.create(this.redisUri);
        this.redisClient.setOptions(clientOptions);
    }

    public static String sessionKey(String rawEnvironment, String sid) {
        return "knode:" + SearchConsumerRuntimeConfig.sanitizeEnvironment(rawEnvironment) + ":v1:auth:session:" + sid;
    }

    @Override
    public Optional<Session> loadSession(String sid) {
        if (!StringUtils.hasText(sid)) {
            return Optional.empty();
        }
        String key = sessionKey(environment, sid);
        String rawJson;
        try {
            rawJson = commands().get(key);
        } catch (Exception ex) {
            throw new AuthFailure(503);
        }
        if (rawJson == null) {
            return Optional.empty();
        }
        return Optional.of(SessionCodec.decode(rawJson));
    }

    private RedisCommands<String, String> commands() {
        synchronized (connectionLock) {
            if (connection == null || !connection.isOpen()) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignored) {
                        // Best-effort cleanup before reopening.
                    }
                }
                connection = redisClient.connect(redisUri);
            }
            return connection.sync();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (connectionLock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // Ignore shutdown errors.
                }
                connection = null;
            }
        }
        redisClient.shutdown();
    }
}
