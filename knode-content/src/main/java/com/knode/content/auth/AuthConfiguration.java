package com.knode.content.auth;

import com.knode.auth.UserTokenVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthConfiguration {
    public record Settings(String environment) {}

    @Bean Settings authSettings(@Value("${KNODE_ENVIRONMENT}") String environment,
                                 @Value("${KNODE_AUTH_REDIS_HOST}") String host,
                                 @Value("${KNODE_AUTH_REDIS_PORT}") int port,
                                 @Value("${KNODE_AUTH_REDIS_USERNAME}") String username,
                                 @Value("${KNODE_AUTH_REDIS_PASSWORD}") String password,
                                 @Value("${spring.data.redis.ssl.enabled}") boolean tls,
                                 @Value("${spring.data.redis.url:}") String redisUrl,
                                 @Value("${KNODE_CONTENT_JDBC_URL}") String jdbcUrl,
                                 @Value("${KNODE_CONTENT_DB_USERNAME}") String dbUsername,
                                 @Value("${KNODE_CONTENT_DB_PASSWORD}") String dbPassword) {
        if (!environment.matches("[A-Za-z0-9_-]{1,32}") || host.isBlank() || port < 1 || port > 65535
                || username.isBlank() || password.isBlank() || !tls || !redisUrl.isBlank() || jdbcUrl.isBlank()
                || dbUsername.isBlank() || dbPassword.isBlank()) {
            throw new IllegalArgumentException("Missing or invalid required content configuration");
        }
        return new Settings(environment);
    }

    @Bean org.springframework.boot.actuate.health.HealthIndicator authSessionHealthIndicator(RedisSessionReader reader) {
        return () -> {
            try {
                reader.read("00000000-0000-0000-0000-000000000000");
                return org.springframework.boot.actuate.health.Health.up().build();
            } catch (RuntimeException failure) {
                return org.springframework.boot.actuate.health.Health.down().build();
            }
        };
    }

    @Bean UserTokenVerifier tokenVerifier(@Value("${KNODE_AUTH_PUBLIC_KEY_FILE:}") String file,
                                           @Value("${KNODE_AUTH_PUBLIC_KEY_ID:}") String kid,
                                           @Value("${KNODE_AUTH_TRUSTED_PUBLIC_KEYS:}") String trustedKeys) throws Exception {
        java.util.LinkedHashMap<String, RSAPublicKey> keys = new java.util.LinkedHashMap<>();
        if (!trustedKeys.isBlank()) {
            String[] entries = trustedKeys.split(";", -1);
            if (entries.length < 1 || entries.length > 4) throw new IllegalArgumentException("Invalid trusted public keys");
            for (String entry : entries) {
                int at = entry.indexOf('=');
                if (at < 1) throw new IllegalArgumentException("Invalid trusted public key entry");
                String entryKid = entry.substring(0, at);
                String entryPath = entry.substring(at + 1);
                if (!entryKid.matches("[A-Za-z0-9._-]{1,128}") || entryPath.isBlank())
                    throw new IllegalArgumentException("Invalid trusted public key entry");
                RSAPublicKey parsed = parsePublicKey(entryPath);
                if (keys.putIfAbsent(entryKid, parsed) != null)
                    throw new IllegalArgumentException("Duplicate trusted public key id");
            }
        }
        if (!file.isBlank() || !kid.isBlank()) {
            if (kid.isBlank() || kid.length() > 128 || file.isBlank()) throw new IllegalArgumentException("Invalid key id");
            RSAPublicKey primary = parsePublicKey(file);
            RSAPublicKey existing = keys.putIfAbsent(kid, primary);
            if (existing != null && !java.util.Arrays.equals(existing.getEncoded(), primary.getEncoded())) {
                throw new IllegalArgumentException("Conflicting public key for kid");
            }
        }
        if (keys.isEmpty() || keys.size() > 4) throw new IllegalArgumentException("At least one trusted public key required");
        return new UserTokenVerifier(Map.copyOf(keys), Clock.systemUTC());
    }

    private static RSAPublicKey parsePublicKey(String file) throws Exception {
        Path path = Path.of(file);
        if (Files.size(path) > 8192) throw new IllegalArgumentException("Public key file too large");
        String pem = Files.readString(path);
        if (!pem.startsWith("-----BEGIN PUBLIC KEY-----") || !pem.contains("-----END PUBLIC KEY-----"))
            throw new IllegalArgumentException("Expected X.509 public key");
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA public key must be at least 2048 bits");
        return key;
    }
}
