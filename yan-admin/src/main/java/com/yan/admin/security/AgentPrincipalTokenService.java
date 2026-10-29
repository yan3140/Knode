package com.yan.admin.security;

import com.yan.admin.config.AgentInternalSecurityProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AgentPrincipalTokenService {

    private static final String ISSUER = "yanblog";
    private static final String AUDIENCE = "yan-agent";

    private final AgentInternalSecurityProperties properties;
    private final Clock clock;

    @Autowired
    public AgentPrincipalTokenService(AgentInternalSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AgentPrincipalTokenService(AgentInternalSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(Long userId, Long knowledgeBaseId) {
        if (userId == null || userId < 1 || knowledgeBaseId == null || knowledgeBaseId < 1) {
            throw new IllegalArgumentException("User and knowledge base ids must be positive");
        }
        int ttlSeconds = properties.getPrincipalJwtTtlSeconds();
        if (ttlSeconds < 1 || ttlSeconds > 300) {
            throw new IllegalStateException("Agent principal JWT TTL must be between 1 and 300 seconds");
        }
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(userId.toString())
                .claim("knowledgeBaseId", knowledgeBaseId)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(ttlSeconds)))
                .signWith(signingKey(), Jwts.SIG.HS256)
                .compact();
    }

    private SecretKey signingKey() {
        byte[] secret = properties.getPrincipalJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("Agent principal JWT secret must contain at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(secret);
    }
}
