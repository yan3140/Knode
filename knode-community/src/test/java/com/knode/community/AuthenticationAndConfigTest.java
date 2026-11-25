package com.knode.community;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.AuthFailure;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.community.auth.AuthConfiguration;
import com.knode.community.auth.SessionReader;
import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"KNODE_MANAGEMENT_PORT=0", "KNODE_COMMUNITY_PORT=0"})
@Import(AuthenticationAndConfigTest.TestFixtures.class)
class AuthenticationAndConfigTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final KeyPair PRIMARY_KEYS = generateRsa(2048);
    static final KeyPair OVERLAP_KEYS = generateRsa(2048);
    static final KeyPair UNTRUSTED_KEYS = generateRsa(2048);
    static final Path PRIMARY_PUB = writePublicKey("community-k1-", PRIMARY_KEYS);
    static final Path OVERLAP_PUB = writePublicKey("community-k2-", OVERLAP_KEYS);
    static final AtomicReference<String> SESSION_STATE = new AtomicReference<>();
    static final AtomicLong NOW_MILLIS = new AtomicLong(Instant.parse("2026-11-24T12:00:00Z").toEpochMilli());
    static final String SID = UUID.randomUUID().toString();
    static final String USER_ID = "9007199254740993";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired JdbcTemplate db;

    static void registerBaseProperties(DynamicPropertyRegistry p, String dbName) {
        p.add("KNODE_ENVIRONMENT", () -> "test");
        p.add("KNODE_AUTH_REDIS_HOST", () -> "127.0.0.1");
        p.add("KNODE_AUTH_REDIS_PORT", () -> "1");
        p.add("KNODE_AUTH_REDIS_USERNAME", () -> "auth_ro");
        p.add("KNODE_AUTH_REDIS_PASSWORD", () -> "secret_ro");
        p.add("KNODE_COMMUNITY_JDBC_URL", () -> "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        p.add("KNODE_COMMUNITY_DB_USERNAME", () -> "sa");
        p.add("KNODE_COMMUNITY_DB_PASSWORD", () -> "test");
        p.add("KNODE_AUTH_TRUSTED_PUBLIC_KEYS", () -> "kid-1=" + PRIMARY_PUB.toAbsolutePath() + ";kid-2=" + OVERLAP_PUB.toAbsolutePath());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        registerBaseProperties(p, "community_auth_test");
    }

    @BeforeEach
    void reset() {
        NOW_MILLIS.set(Instant.parse("2026-11-24T12:00:00Z").toEpochMilli());
        setActiveSession("blog", USER_ID);
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("db/community-schema.sql")).execute(db.getDataSource());
    }

    static void setActiveSession(String scope, String userId) {
        long expSec = (NOW_MILLIS.get() / 1000L) + 1800L;
        SESSION_STATE.set(SessionCodec.encode(new Session(1, SID, userId, scope, "ACTIVE", expSec)));
    }

    static String userToken(String kid, KeyPair pair, String userId, String scope, int ttlSeconds) {
        Instant now = Instant.ofEpochMilli(NOW_MILLIS.get());
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer("knode-user")
                .subject(userId)
                .audience().add("knode-api").and()
                .claim("sid", SID)
                .claim("scope", scope)
                .claim("token_use", "user")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(5)))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(pair.getPrivate())
                .compact();
    }

    static String serviceToken(String kid, KeyPair pair, String sub, String aud, List<String> permissions) {
        Instant now = Instant.ofEpochMilli(NOW_MILLIS.get());
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer("knode-user")
                .subject(sub)
                .audience().add(aud).and()
                .claim("token_use", "service")
                .claim("permissions", permissions)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(2)))
                .expiration(Date.from(now.plusSeconds(120)))
                .signWith(pair.getPrivate())
                .compact();
    }

    @Test
    void multiKidRotationWorksForBothUserAndServiceTokens() throws Exception {
        String tokenK1 = userToken("kid-1", PRIMARY_KEYS, USER_ID, "blog", 600);
        String tokenK2 = userToken("kid-2", OVERLAP_KEYS, USER_ID, "blog", 600);
        String tokenUntrusted = userToken("kid-x", UNTRUSTED_KEYS, USER_ID, "blog", 600);

        assertEquals(200, send("GET", "/api/blog/follow/status/42", tokenK1, null).statusCode());
        assertEquals(200, send("GET", "/follow/status/42", tokenK2, null).statusCode());
        assertEquals(401, send("GET", "/api/blog/follow/status/42", tokenUntrusted, null).statusCode());

        String svcK1 = serviceToken("kid-1", PRIMARY_KEYS, "knode-content", "knode-community", List.of("community:relations:read"));
        String svcK2 = serviceToken("kid-2", OVERLAP_KEYS, "knode-content", "knode-community", List.of("community:relations:read"));
        assertEquals(200, sendInternal("POST", "/internal/v1/community/relations:batch", svcK1, "{\"userIds\":[\"42\"]}").statusCode());
        assertEquals(200, sendInternal("POST", "/internal/v1/community/relations:batch", svcK2, "{\"userIds\":[\"42\"]}").statusCode());
    }

    @Test
    void enforcesSessionRevocationScopeRedisOutageAndHeaderHygiene() throws Exception {
        String validToken = userToken("kid-1", PRIMARY_KEYS, USER_ID, "blog", 600);

        assertEquals(401, send("GET", "/api/blog/follow/following", null, null).statusCode());
        assertEquals(401, send("GET", "/api/blog/follow/following", "not-a-jwt", null).statusCode());
        assertEquals(401, send("GET", "/api/blog/follow/following", userToken("kid-1", PRIMARY_KEYS, USER_ID, "blog", -10), null).statusCode());

        // /follow/status/following without token must require authentication (never treated as public /{userId}/following)
        assertEquals(401, send("GET", "/api/blog/follow/status/following", null, null).statusCode());
        assertEquals(401, send("GET", "/follow/status/following", null, null).statusCode());

        for (String forbidden : List.of("X-User-Id", "X-Internal-Sub", "X-Viewer-Id", "X-Actor", "X-Operator", "viewerId")) {
            HttpRequest spoofed = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/blog/follow/status/42"))
                    .header("token", validToken).header(forbidden, "1").GET().build();
            assertEquals(401, HTTP.send(spoofed, HttpResponse.BodyHandlers.ofString()).statusCode(), forbidden);
        }
        assertEquals(401, send("GET", "/api/blog/follow/status/42", validToken + "," + validToken, null).statusCode());
        HttpRequest dupToken = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/blog/follow/status/42"))
                .header("token", validToken).header("token", validToken).GET().build();
        assertEquals(401, HTTP.send(dupToken, HttpResponse.BodyHandlers.ofString()).statusCode());

        SESSION_STATE.set(null);
        assertEquals(401, send("GET", "/api/blog/follow/status/42", validToken, null).statusCode());
        SESSION_STATE.set(SessionCodec.encode(new Session(1, SID, USER_ID, "blog", "REVOKED", (NOW_MILLIS.get() / 1000L) + 1800L)));
        assertEquals(401, send("GET", "/api/blog/follow/status/42", validToken, null).statusCode());

        setActiveSession("admin", USER_ID);
        var adminDenied = send("GET", "/api/blog/follow/status/42", userToken("kid-1", PRIMARY_KEYS, USER_ID, "admin", 600), null);
        assertEquals(403, adminDenied.statusCode());
        assertEquals(403, JSON.readTree(adminDenied.body()).path("code").asInt());

        SESSION_STATE.set("UNAVAILABLE");
        var unavailable = send("GET", "/api/blog/follow/status/42", validToken, null);
        assertEquals(503, unavailable.statusCode());
        assertEquals(500, JSON.readTree(unavailable.body()).path("code").asInt());

        SESSION_STATE.set("{corrupt-json");
        assertEquals(503, send("GET", "/api/blog/follow/status/42", validToken, null).statusCode());
    }

    @Test
    void normalizesRequestIdsAndExposesManagementReadiness() throws Exception {
        setActiveSession("blog", USER_ID);
        String validToken = userToken("kid-1", PRIMARY_KEYS, USER_ID, "blog", 600);

        HttpRequest validReqId = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/blog/follow/status/42"))
                .header("token", validToken).header("X-Request-Id", "req-good_01").GET().build();
        var preserved = HTTP.send(validReqId, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, preserved.statusCode());
        assertEquals("req-good_01", preserved.headers().firstValue("X-Request-Id").orElseThrow());

        HttpRequest badReqId = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/blog/follow/status/42"))
                .header("token", validToken).header("X-Request-Id", "bad space!").GET().build();
        var regenerated = HTTP.send(badReqId, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, regenerated.statusCode());
        assertNotEquals("bad space!", regenerated.headers().firstValue("X-Request-Id").orElseThrow());

        HttpRequest readiness = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/readiness"))
                .GET().build();
        assertEquals(503, HTTP.send(readiness, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void keyRingParserRejectsWeakDuplicateOrMalformedKeys() {
        KeyPair weak1024 = generateRsa(1024);
        Path weakPath = writePublicKey("weak-1024-", weak1024);
        assertThrows(IllegalArgumentException.class,
                () -> AuthConfiguration.parsePublicKey(weakPath.toString(), true));
        assertThrows(IllegalArgumentException.class,
                () -> AuthConfiguration.parseKeyRingSpec("k1=" + PRIMARY_PUB.toAbsolutePath() + ";k1=" + OVERLAP_PUB.toAbsolutePath(), true));
        assertThrows(IllegalArgumentException.class,
                () -> AuthConfiguration.parseKeyRingSpec("relative/path.pub", true));
    }

    private HttpResponse<String> send(String method, String path, String userToken, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (userToken != null) b.header("token", userToken);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendInternal(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static KeyPair generateRsa(int bits) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(bits);
            return g.generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Path writePublicKey(String prefix, KeyPair pair) {
        try {
            Path path = Files.createTempFile(prefix, ".pub");
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
            Files.writeString(path, pem);
            path.toFile().deleteOnExit();
            return path;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @TestConfiguration
    static class TestFixtures {
        @Bean
        @Primary
        Clock testClock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return Instant.ofEpochMilli(NOW_MILLIS.get()); }
            };
        }

        @Bean
        @Primary
        SessionReader sessionReader() {
            return sid -> {
                String val = SESSION_STATE.get();
                if ("UNAVAILABLE".equals(val)) throw new AuthFailure(503);
                return Optional.ofNullable(val);
            };
        }
    }
}
