package com.knode.gateway;

import com.knode.auth.UserTokenVerifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

@Configuration
@ConditionalOnProperty(name = "knode.gateway.auth.configured", havingValue = "true")
public class AuthenticationConfiguration {
    @Bean
    AuthMaterial authMaterial(@Value("${knode.gateway.auth.public-keys:}") String publicKeys,
                              @Value("${knode.gateway.auth.timeout-ms:1000}") long timeoutMs) throws Exception {
        if (timeoutMs < 1 || timeoutMs > 10000 || publicKeys.isBlank()) throw new IllegalArgumentException("Authentication configuration incomplete");
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        for (String item : publicKeys.split(";", -1)) {
            int separator = item.indexOf('=');
            if (separator < 1 || separator == item.length() - 1) throw new IllegalArgumentException("Invalid key mapping");
            String kid = item.substring(0, separator);
            if (!kid.matches("[A-Za-z0-9._-]{1,64}") || keys.containsKey(kid)) throw new IllegalArgumentException("Invalid key id");
            Path path = Path.of(item.substring(separator + 1));
            if (Files.size(path) > 16384) throw new IllegalArgumentException("Public key file too large");
            String pem = Files.readString(path, StandardCharsets.US_ASCII).trim();
            String begin = "-----BEGIN PUBLIC KEY-----", end = "-----END PUBLIC KEY-----";
            if (!pem.startsWith(begin) || !pem.endsWith(end)) throw new IllegalArgumentException("Only public keys are allowed");
            String encoded = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            keys.put(kid, key);
        }
        return new AuthMaterial(new UserTokenVerifier(keys, Clock.systemUTC()), Duration.ofMillis(timeoutMs));
    }

    @Bean(destroyMethod = "destroy")
    LettuceConnectionFactory authRedisConnection(
            @Value("${knode.gateway.auth.redis-host:}") String host,
            @Value("${knode.gateway.auth.redis-port:0}") int port,
            @Value("${knode.gateway.auth.redis-username:}") String username,
            @Value("${knode.gateway.auth.redis-password:}") String password,
            @Value("${knode.gateway.auth.redis-database:0}") int database,
            @Value("${knode.gateway.auth.timeout-ms:1000}") long timeoutMs) {
        if (host.isBlank() || username.isBlank() || password.isBlank() || port < 1 || port > 65535 || database < 0 || timeoutMs < 1 || timeoutMs > 10000)
            throw new IllegalArgumentException("Authentication Redis configuration incomplete");
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(database);
        if (!username.isBlank()) config.setUsername(username);
        if (!password.isBlank()) config.setPassword(RedisPassword.of(password));
        return new LettuceConnectionFactory(config, LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(timeoutMs)).build());
    }

    @Bean
    SessionReader sessionReader(LettuceConnectionFactory authRedisConnection,
            @Value("${knode.gateway.auth.environment:}") String environment) {
        if (!environment.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Authentication environment missing or invalid");
        String prefix = "knode:" + environment + ":v1:auth:session:";
        ReactiveStringRedisTemplate redis = new ReactiveStringRedisTemplate(authRedisConnection);
        return new SessionReader() {
            @Override public reactor.core.publisher.Mono<String> get(String sid) { return redis.opsForValue().get(prefix + sid); }
            @Override public reactor.core.publisher.Mono<Void> checkAvailability() { return redis.opsForValue().get(prefix + "__readiness__").then(); }
        };
    }
}
