package com.knode.gateway;

import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.auth.UserTokenVerifier;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.discovery.enabled=false"})
@Import(GatewayAuthenticationTest.Configuration.class)
class GatewayAuthenticationTest {
    static final KeyPair PAIR = keyPair();
    static final MutableClock CLOCK = new MutableClock();
    static final FakeReader READER = new FakeReader();
    static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
        UPSTREAM_CALLS.incrementAndGet();
        return res.sendString(Mono.just(req.uri() + "|" + req.requestHeaders().get("token") + "|"
                + req.requestHeaders().get("X-Identity-Id") + "|" + req.requestHeaders().get("Authorization") + "|"
                + req.requestHeaders().get("X-Forwarded-For") + "|" + req.requestHeaders().get("X-Real-IP") + "|"
                + java.util.stream.Stream.of("X-Internal-Role","X-User-Role","X-Principal-Name","X-Service-Name",
                        "X-Identity-Id","X-Authenticated-Id","X-Forwarded-User","X-Remote-User","X-Auth-Request-User")
                        .filter(req.requestHeaders()::contains).findAny().orElse("none")));
    }).bindNow();

    static KeyPair keyPair() {
        try { KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static class MutableClock extends Clock {
        final AtomicLong now = new AtomicLong(Instant.now().getEpochSecond());
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(now.get()); }
    }
    static class FakeReader implements SessionReader {
        final Map<String, String> sessions = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicReference<String> mode = new AtomicReference<>("ok");
        @Override public Mono<String> get(String sid) {
            return Mono.defer(() -> {
                reads.incrementAndGet();
                if (mode.get().equals("error")) return Mono.error(new IllegalStateException("offline"));
                if (mode.get().equals("timeout")) return Mono.never();
                if (mode.get().equals("expire")) CLOCK.now.addAndGet(3601);
                return Mono.justOrEmpty(sessions.get(sid));
            });
        }
        @Override public Mono<Void> checkAvailability() {
            return mode.get().equals("error") ? Mono.error(new IllegalStateException("offline")) :
                    mode.get().equals("timeout") ? Mono.never() : Mono.empty();
        }
    }
    @TestConfiguration static class Configuration {
        @Bean AuthMaterial testMaterial() {
            return new AuthMaterial(new UserTokenVerifier(Map.of("test-key", (RSAPublicKey) PAIR.getPublic()), CLOCK),
                    Duration.ofMillis(150));
        }
        @Bean SessionReader testReader() { return READER; }
    }
    @DynamicPropertySource static void routes(DynamicPropertyRegistry r) {
        for (String domain : new String[]{"user","content","community","search","knowledge"})
            r.add("knode.gateway." + domain + "-uri", () -> "http://127.0.0.1:" + UPSTREAM.port());
    }
    @AfterAll static void shutdown() { UPSTREAM.disposeNow(); }
    @LocalServerPort int port;
    WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build(); }

    @BeforeEach void setup() {
        CLOCK.now.set(Instant.now().getEpochSecond());
        READER.mode.set("ok"); READER.sessions.clear(); READER.reads.set(0); UPSTREAM_CALLS.set(0);
    }
    String token(String sid, String scope, String issuer) {
        Instant now = CLOCK.instant();
        return Jwts.builder().header().keyId("test-key").and().issuer(issuer).subject("42")
                .audience().add("knode-api").and().claim("token_use","user").claim("sid",sid).claim("scope",scope)
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600)))
                .signWith((RSAPrivateKey)PAIR.getPrivate(), Jwts.SIG.RS256).compact();
    }
    String tokenVariant(String sid, String audience, String usage, long lifetime) {
        Instant now = CLOCK.instant();
        return Jwts.builder().header().keyId("test-key").and().issuer("knode-user").subject("42")
                .audience().add(audience).and().claim("token_use", usage).claim("sid",sid).claim("scope","blog")
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now.minusSeconds(5)))
                .expiration(Date.from(now.plusSeconds(lifetime)))
                .signWith((RSAPrivateKey)PAIR.getPrivate(), Jwts.SIG.RS256).compact();
    }

    String session(String sid, String scope) {
        return SessionCodec.encode(new Session(1, sid, "42", scope, "ACTIVE", CLOCK.instant().getEpochSecond() + 3600));
    }

    @Test void validUserIsRecheckedAndRevocationStopsDownstream() {
        String sid = UUID.randomUUID().toString(), jwt = token(sid, "blog", "knode-user");
        READER.sessions.put(sid, session(sid,"blog"));
        for (int i=0; i<2; i++) client().get().uri("/api/blog/user/userInfo")
                .header("token",jwt).header("X-Identity-Id","fake").header("Authorization","fake")
                .header("X-Internal-Role","fake").header("X-User-Role","fake").header("X-Principal-Name","fake")
                .header("X-Service-Name","fake").header("X-Authenticated-Id","fake").header("X-Forwarded-User","fake")
                .header("X-Remote-User","fake").header("X-Auth-Request-User","fake")
                .header("X-Forwarded-For","203.0.113.99").header("X-Real-IP","203.0.113.99")
                .exchange().expectStatus().isOk().expectBody(String.class).value(body -> {
                    assertThat(body).startsWith("/api/blog/user/userInfo|" + jwt + "|null|null|");
                    assertThat(body).doesNotContain("203.0.113.99").endsWith("|none");
                });
        assertThat(READER.reads.get()).isEqualTo(2);
        READER.sessions.remove(sid);
        client().get().uri("/api/blog/user/userInfo").header("token",jwt).exchange().expectStatus().isUnauthorized();
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(2);
    }

    @Test void identityAndAuthorityFailuresNeverReachUpstream() {
        String sid=UUID.randomUUID().toString(), jwt=token(sid,"blog","knode-user");
        READER.sessions.put(sid, session(sid,"blog"));
        int sig = jwt.lastIndexOf('.') + 1;
        String badSignature = jwt.substring(0, sig) + (jwt.charAt(sig) == 'A' ? "B" : "A") + jwt.substring(sig + 1);
        client().get().uri("/api/blog/user/userInfo").header("token",badSignature).exchange().expectStatus().isUnauthorized();
        assertThat(READER.reads.get()).isZero();
        for (String bad : new String[]{tokenVariant(sid, "other-api", "user", 100),
                tokenVariant(sid, "knode-api", "service", 100), tokenVariant(sid, "knode-api", "user", -1)}) {
            client().get().uri("/api/blog/user/userInfo").header("token",bad).exchange().expectStatus().isUnauthorized();
        }
        assertThat(READER.reads.get()).isZero();
        client().get().uri("/api/admin/auth/me").header("token",jwt).exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(403);
        READER.sessions.remove(sid);
        client().get().uri("/api/admin/auth/me").header("token",jwt).exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo(401);
        client().get().uri("/api/blog/user/userInfo").header("token",token(sid,"blog","bad-issuer"))
                .exchange().expectStatus().isUnauthorized();
        READER.sessions.put(sid, SessionCodec.encode(new Session(1, sid, "99", "blog", "ACTIVE", CLOCK.instant().getEpochSecond()+3600)));
        client().get().uri("/api/blog/user/userInfo").header("token",jwt).exchange().expectStatus().isUnauthorized();
        READER.sessions.put(sid, SessionCodec.encode(new Session(1, sid, "42", "blog", "REVOKED", CLOCK.instant().getEpochSecond()+3600)));
        client().get().uri("/api/blog/user/userInfo").header("token",jwt).exchange().expectStatus().isUnauthorized();
        READER.sessions.put(sid, "not-json");
        client().get().uri("/api/blog/user/userInfo").header("token",jwt).exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo(500);
        READER.mode.set("error");
        client().get().uri("/api/blog/user/userInfo").header("token",jwt).exchange().expectStatus().isEqualTo(503);
        READER.mode.set("timeout");
        client().get().uri("/api/blog/user/userInfo").header("token",jwt).exchange().expectStatus().isEqualTo(503);
        READER.mode.set("expire"); READER.sessions.put(sid, session(sid,"blog"));
        client().get().uri("/api/blog/user/userInfo").header("token",jwt).exchange().expectStatus().isUnauthorized();
        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @Test void anonymousReadsAndCredentialEndpointsAreMethodScoped() {
        client().get().uri("/api/blog/article/articleList").exchange().expectStatus().isOk();
        client().put().uri("/api/blog/article/updateViewCount/42").exchange().expectStatus().isOk();
        client().get().uri("/api/blog/article/articleList").header("token","invalid")
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/api/blog/article").exchange().expectStatus().isUnauthorized();
        client().post().uri("/api/blog/auth/login").header("token","expired-old-token")
                .bodyValue("{}").exchange().expectStatus().isOk().expectBody(String.class).value(body ->
                        assertThat(body).startsWith("/api/blog/auth/login|null|"));
        client().post().uri("/api/admin/auth/login").header("token","expired-old-token")
                .bodyValue("{}").exchange().expectStatus().isOk();
        client().post().uri("/api/blog/user/register").header("token","expired-old-token")
                .bodyValue("{}").exchange().expectStatus().isOk();
        client().get().uri("/api/blog/user/userInfo").header("token","a","b").exchange().expectStatus().isBadRequest();
        client().get().uri("/internal/x").exchange().expectStatus().isForbidden();
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(5);
    }

    @Test void publicReadValidatesOptionalAdminSessionWithoutImposingBlogScope() {
        String sid = UUID.randomUUID().toString(), jwt = token(sid,"admin","knode-user");
        READER.sessions.put(sid, session(sid,"admin"));
        client().get().uri("/api/blog/article/articleList").header("token",jwt).exchange().expectStatus().isOk();
        client().get().uri("/api/blog/user/userInfo").header("token",jwt).exchange().expectStatus().isForbidden();
        READER.sessions.remove(sid);
        client().get().uri("/api/blog/article/articleList").header("token",jwt).exchange().expectStatus().isUnauthorized();
        assertThat(READER.reads.get()).isEqualTo(3);
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
    }

    @Test void readinessChecksAuthorityButLivenessStaysUp() {
        client().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        READER.mode.set("error");
        client().get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(503);
        client().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    }
}
