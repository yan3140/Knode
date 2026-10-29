package com.yan.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.yan.admin.config.AgentInternalSecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.Test;

class AgentPrincipalTokenServiceTest {

    @Test
    void issuesShortLivedTokenScopedToUserAndKnowledgeBase() {
        AgentInternalSecurityProperties properties = new AgentInternalSecurityProperties();
        properties.setPrincipalJwtSecret("0123456789abcdef0123456789abcdef");
        properties.setPrincipalJwtTtlSeconds(60);
        Clock clock = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC);
        AgentPrincipalTokenService tokens = new AgentPrincipalTokenService(properties, clock);

        String token = tokens.issue(3L, 5L);

        Jws<Claims> signedToken = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(properties.getPrincipalJwtSecret()
                        .getBytes(StandardCharsets.UTF_8)))
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token);
        Claims claims = signedToken.getPayload();
        assertThat(signedToken.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(claims.getSubject()).isEqualTo("3");
        assertThat(claims.get("knowledgeBaseId", Long.class)).isEqualTo(5L);
        assertThat(claims.getIssuer()).isEqualTo("yanblog");
        assertThat(claims.getAudience()).containsExactly("yan-agent");
        assertThat(claims.getExpiration().toInstant())
                .isEqualTo(Instant.parse("2026-08-07T12:01:00Z"));
    }
}
