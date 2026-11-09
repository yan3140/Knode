package com.knode.content;

import com.knode.auth.AuthFailure;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.content.auth.AuthenticationFilter;
import com.knode.content.auth.SessionReader;
import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"KNODE_MANAGEMENT_PORT=0", "KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class AuthenticationHttpTest {
    static final KeyPair KEYS = keys();
    static final Path PUBLIC_KEY = publicKey();
    static final AtomicReference<String> STATE = new AtomicReference<>();
    static final AtomicInteger HITS = new AtomicInteger();
    static final String SID = UUID.randomUUID().toString();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @LocalManagementPort int managementPort;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("KNODE_ENVIRONMENT", () -> "test");
        p.add("KNODE_MANAGEMENT_PORT", () -> "0");
        p.add("KNODE_AUTH_REDIS_HOST", () -> "127.0.0.1");
        p.add("KNODE_AUTH_REDIS_PORT", () -> "1");
        p.add("KNODE_AUTH_REDIS_USERNAME", () -> "test");
        p.add("KNODE_AUTH_REDIS_PASSWORD", () -> "test");
        p.add("KNODE_CONTENT_JDBC_URL", () -> "jdbc:h2:mem:content;MODE=MySQL;DB_CLOSE_DELAY=-1");
        p.add("KNODE_CONTENT_DB_USERNAME", () -> "sa");
        p.add("KNODE_CONTENT_DB_PASSWORD", () -> "test");
        p.add("KNODE_AUTH_PUBLIC_KEY_FILE", () -> PUBLIC_KEY.toString());
        p.add("KNODE_AUTH_PUBLIC_KEY_ID", () -> "test-key");
    }
    @BeforeEach void reset() {
        HITS.set(0);
        STATE.set(SessionCodec.encode(new Session(1, SID, "9007199254740993", "blog", "ACTIVE", Instant.now().getEpochSecond() + 1800)));
    }
    @Test void validTokenAndPrincipal() throws Exception {
        var r = get("/api/blog/test-only", token("blog", 600), null, null);
        assertEquals(200, r.statusCode());
        assertEquals("9007199254740993", r.body());
        assertEquals(1, HITS.get());
        assertTrue(r.headers().firstValue("X-Request-Id").isPresent());
    }
    @Test void revokedSessionRejectsSameToken() throws Exception {
        String token = token("blog", 600);
        assertEquals(200, get("/api/blog/test-only", token, null, null).statusCode());
        STATE.set(null);
        assertEquals(401, get("/api/blog/test-only", token, null, null).statusCode());
        assertEquals(1, HITS.get());
    }
    @Test void revokedStatusIsRejected() throws Exception {
        STATE.set(SessionCodec.encode(new Session(1, SID, "9007199254740993", "blog", "REVOKED", Instant.now().getEpochSecond() + 1800)));
        assertEquals(401, get("/api/blog/test-only", token("blog", 600), null, null).statusCode());
        assertEquals(0, HITS.get());
    }
    @Test void scopeDeniedOnlyAfterSessionValidation() throws Exception {
        var forbidden = get("/api/admin/test-only", token("blog", 600), null, null);
        assertEquals(403, forbidden.statusCode());
        assertTrue(forbidden.body().contains("\"code\":403"));
        STATE.set(null);
        assertEquals(401, get("/api/admin/test-only", token("blog", 600), null, null).statusCode());
        assertEquals(0, HITS.get());
    }
    @Test void malformedAndUnavailableSessionAre503() throws Exception {
        STATE.set("{bad");
        assertEquals(503, get("/api/blog/test-only", token("blog", 600), null, null).statusCode());
        STATE.set("UNAVAILABLE");
        var r = get("/api/blog/test-only", token("blog", 600), null, null);
        assertEquals(503, r.statusCode());
        assertFalse(r.body().contains("UNAVAILABLE"));
        assertTrue(r.body().contains("\"code\":500"));
        assertEquals(0, HITS.get());
    }
    @Test void invalidExpiredAndMissingTokenAre401() throws Exception {
        assertEquals(401, get("/api/blog/test-only", null, null, null).statusCode());
        var invalid = get("/api/blog/test-only", "bad", null, null);
        assertEquals(401, invalid.statusCode());
        assertTrue(invalid.body().contains("\"code\":401"));
        assertEquals(401, get("/api/blog/test-only", token("blog", -1), null, null).statusCode());
        assertEquals(0, HITS.get());
    }
    @Test void rejectsIdentityAndRepeatedOrMergedToken() throws Exception {
        String t = token("blog", 600);
        assertEquals(401, get("/api/blog/test-only", t, "X-User-Id", "42").statusCode());
        assertEquals(401, get("/api/blog/test-only", t + "," + t, null, null).statusCode());
        HttpRequest q = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/blog/test-only"))
                .header("token", t).header("token", t).GET().build();
        assertEquals(401, HTTP.send(q, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, HITS.get());
    }
    @Test void mismatchedSessionBindingIs401() throws Exception {
        STATE.set(SessionCodec.encode(new Session(1, SID, "43", "blog", "ACTIVE", Instant.now().getEpochSecond() + 1800)));
        assertEquals(401, get("/api/blog/test-only", token("blog", 600), null, null).statusCode());
        assertEquals(0, HITS.get());
    }
    @Test void malformedSessionSemanticsAre503() throws Exception {
        STATE.set(SessionCodec.encode(new Session(2, SID, "42", "blog", "ACTIVE", Instant.now().getEpochSecond() + 1200)));
        assertEquals(503, get("/api/blog/test-only", token("blog", 600), null, null).statusCode());
        assertEquals(0, HITS.get());
    }
    @Test void requestIdsAreValidatedAndDuplicatesRegenerated() throws Exception {
        String t = token("blog", 600);
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/blog/test-only");
        HttpRequest bad = HttpRequest.newBuilder(uri).header("token", t).header("X-Request-Id", "bad id").GET().build();
        var invalid = HTTP.send(bad, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, invalid.statusCode());
        assertNotEquals("bad id", invalid.headers().firstValue("X-Request-Id").orElseThrow());
        HttpRequest duplicate = HttpRequest.newBuilder(uri).header("token", t)
                .header("X-Request-Id", "one").header("X-Request-Id", "two").GET().build();
        var repeated = HTTP.send(duplicate, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, repeated.statusCode());
        assertNotEquals("one", repeated.headers().firstValue("X-Request-Id").orElseThrow());
        assertNotEquals("two", repeated.headers().firstValue("X-Request-Id").orElseThrow());
    }
    @Test void readinessReportsUnavailableRedis() throws Exception {
        HttpRequest q = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/readiness")).GET().build();
        assertEquals(503, HTTP.send(q, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    private HttpResponse<String> get(String path, String token, String name, String value) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (token != null) b.header("token", token);
        if (name != null) b.header(name, value);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    static String token(String scope, int seconds) {
        Instant now = Instant.now();
        return Jwts.builder().header().keyId("test-key").and().issuer("knode-user").subject("9007199254740993")
                .audience().add("knode-api").and().claim("sid", SID).claim("scope", scope).claim("token_use", "user")
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now.minusSeconds(10)))
                .expiration(Date.from(now.plusSeconds(seconds))).signWith(KEYS.getPrivate()).compact();
    }
    private static KeyPair keys() {
        try { KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static Path publicKey() {
        try {
            Path path = Files.createTempFile("knode-content-test-", ".pub");
            Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
            path.toFile().deleteOnExit(); return path;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @TestConfiguration static class Fixtures {
        @Bean @Primary SessionReader sessionReader() {
            return sid -> {
                String value = STATE.get();
                if ("UNAVAILABLE".equals(value)) throw new AuthFailure(503);
                return Optional.ofNullable(value);
            };
        }
        @Bean Probe probe() { return new Probe(); }
    }
    @RestController static class Probe {
        @GetMapping({"/api/blog/test-only", "/api/admin/test-only"})
        String get(HttpServletRequest request) {
            HITS.incrementAndGet();
            return ((com.knode.auth.UserTokenVerifier.Principal) request.getAttribute(AuthenticationFilter.PRINCIPAL)).userId();
        }
    }
}
