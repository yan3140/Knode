package com.knode.community.auth;

import com.knode.auth.UserTokenVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthConfiguration {
    public record Settings(String environment) {}
    public record TrustedKeyRing(Map<String, RSAPublicKey> keys) {}

    @Bean
    Clock communityClock() {
        return Clock.systemUTC();
    }

    @Bean
    Settings authSettings(
            @Value("${KNODE_ENVIRONMENT}") String environment,
            @Value("${KNODE_AUTH_REDIS_HOST}") String host,
            @Value("${KNODE_AUTH_REDIS_PORT}") int port,
            @Value("${KNODE_AUTH_REDIS_USERNAME}") String username,
            @Value("${KNODE_AUTH_REDIS_PASSWORD}") String password,
            @Value("${spring.data.redis.ssl.enabled}") boolean tls,
            @Value("${spring.data.redis.url:}") String redisUrl,
            @Value("${KNODE_COMMUNITY_JDBC_URL}") String jdbcUrl,
            @Value("${KNODE_COMMUNITY_DB_USERNAME}") String dbUsername,
            @Value("${KNODE_COMMUNITY_DB_PASSWORD}") String dbPassword) {
        if (!environment.matches("[A-Za-z0-9_-]{1,32}")
                || host.isBlank()
                || port < 1
                || port > 65535
                || username.isBlank()
                || password.isBlank()
                || !tls
                || !redisUrl.isBlank()
                || jdbcUrl.isBlank()
                || dbUsername.isBlank()
                || dbPassword.isBlank()) {
            throw new IllegalArgumentException("Missing or invalid required community configuration");
        }
        return new Settings(environment);
    }

    @Bean
    HealthIndicator authSessionHealthIndicator(RedisSessionReader reader) {
        return () -> {
            try {
                reader.read("00000000-0000-0000-0000-000000000000");
                return Health.up().build();
            } catch (RuntimeException failure) {
                return Health.down().build();
            }
        };
    }

    @Bean
    TrustedKeyRing trustedKeyRing(
            @Value("${KNODE_AUTH_PUBLIC_KEY_FILE:}") String file,
            @Value("${KNODE_AUTH_PUBLIC_KEY_ID:}") String kid,
            @Value("${KNODE_AUTH_TRUSTED_PUBLIC_KEYS:}") String trustedKeys) throws Exception {
        LinkedHashMap<String, RSAPublicKey> keys = new LinkedHashMap<>();
        if (!trustedKeys.isBlank()) {
            keys.putAll(parseKeyRingSpec(trustedKeys, false));
        }
        if (!file.isBlank() || !kid.isBlank()) {
            if (!kid.matches("[A-Za-z0-9._-]{1,128}") || file.isBlank()) {
                throw new IllegalArgumentException("Invalid public key id or file");
            }
            RSAPublicKey primary = parsePublicKey(file, false);
            RSAPublicKey existing = keys.putIfAbsent(kid, primary);
            if (existing != null && !Arrays.equals(existing.getEncoded(), primary.getEncoded())) {
                throw new IllegalArgumentException("Conflicting public key for kid");
            }
        }
        if (keys.isEmpty() || keys.size() > 4) {
            throw new IllegalArgumentException("Between one and four trusted public keys are required");
        }
        return new TrustedKeyRing(Map.copyOf(keys));
    }

    @Bean
    UserTokenVerifier tokenVerifier(TrustedKeyRing ring, Clock clock) {
        return new UserTokenVerifier(ring.keys(), clock);
    }

    public static Map<String, RSAPublicKey> parseKeyRingSpec(String spec, boolean requireAbsolutePath) throws Exception {
        String[] entries = spec.split(";", -1);
        if (entries.length < 1 || entries.length > 4) {
            throw new IllegalArgumentException("Invalid trusted public keys");
        }
        LinkedHashMap<String, RSAPublicKey> keys = new LinkedHashMap<>();
        for (String entry : entries) {
            int at = entry.indexOf('=');
            if (at < 1) {
                throw new IllegalArgumentException("Invalid trusted public key entry");
            }
            String entryKid = entry.substring(0, at);
            String entryPath = entry.substring(at + 1);
            if (!entryKid.matches("[A-Za-z0-9._-]{1,128}") || entryPath.isBlank()) {
                throw new IllegalArgumentException("Invalid trusted public key entry");
            }
            RSAPublicKey parsed = parsePublicKey(entryPath, requireAbsolutePath);
            if (keys.putIfAbsent(entryKid, parsed) != null) {
                throw new IllegalArgumentException("Duplicate trusted public key id");
            }
        }
        return Map.copyOf(keys);
    }

    public static RSAPublicKey parsePublicKey(String file, boolean requireAbsolutePath) throws Exception {
        Path path = Path.of(file);
        if (requireAbsolutePath && !path.isAbsolute()) {
            throw new IllegalArgumentException("Public key path must be absolute");
        }
        if (Files.size(path) > 8192) {
            throw new IllegalArgumentException("Public key file too large");
        }
        String pem = Files.readString(path);
        if (!pem.startsWith("-----BEGIN PUBLIC KEY-----") || !pem.contains("-----END PUBLIC KEY-----")) {
            throw new IllegalArgumentException("Expected X.509 public key");
        }
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        if (key.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("RSA public key must be at least 2048 bits");
        }
        return key;
    }
}
