package com.knode.content;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.content.auth.AuthConfiguration;
import com.knode.content.profile.UserProfileClient;
import com.knode.governance.GovernedCall;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"KNODE_MANAGEMENT_PORT=0", "KNODE_CONTENT_PORT=0"})
@AutoConfigureObservability
@Import(AuthenticationHttpTest.Fixtures.class)
class RuntimeWiringTest {
    static final KeyPair ROTATED_KEYS = genKeys();
    static final Path ROTATED_PUBLIC_KEY = writePublicKey(ROTATED_KEYS);

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;

    @DynamicPropertySource static void props(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_AUTH_TRUSTED_PUBLIC_KEYS", () -> "test-key=" + AuthenticationHttpTest.PUBLIC_KEY + ";rotated-key=" + ROTATED_PUBLIC_KEY);
    }

    @BeforeEach void reset() {
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(
                new Session(1, AuthenticationHttpTest.SID, "9007199254740993", "blog", "ACTIVE", Instant.now().getEpochSecond() + 1800)));
        FlowRuleManager.loadRules(List.of());
    }

    @AfterEach void cleanupRules() {
        FlowRuleManager.loadRules(List.of());
    }

    @Test void overlappingMultiKidUserJwtRotationWorksAndRejectsUnknownKid() throws Exception {
        String primaryToken = sign("test-key", AuthenticationHttpTest.KEYS);
        String rotatedToken = sign("rotated-key", ROTATED_KEYS);
        String unknownToken = sign("unknown-kid", ROTATED_KEYS);

        assertEquals(200, get("/api/blog/test-only", primaryToken).statusCode());
        assertEquals(200, get("/api/blog/test-only", rotatedToken).statusCode());
        assertEquals(401, get("/api/blog/test-only", unknownToken).statusCode());
    }

    @Test void malformedTrustedPublicKeysFailStartupClosed() {
        new ApplicationContextRunner().withUserConfiguration(AuthConfiguration.class)
                .withPropertyValues(
                        "KNODE_ENVIRONMENT=test",
                        "KNODE_AUTH_REDIS_HOST=127.0.0.1", "KNODE_AUTH_REDIS_PORT=1",
                        "KNODE_AUTH_REDIS_USERNAME=test", "KNODE_AUTH_REDIS_PASSWORD=test",
                        "spring.data.redis.ssl.enabled=true",
                        "KNODE_CONTENT_JDBC_URL=jdbc:h2:mem:test", "KNODE_CONTENT_DB_USERNAME=sa",
                        "KNODE_CONTENT_DB_PASSWORD=test",
                        "KNODE_AUTH_TRUSTED_PUBLIC_KEYS=bad-entry-without-equals")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test void prometheusEndpointIsExposedOnLoopbackManagementPort() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).GET().build();
        HttpResponse<String> resp = AuthenticationHttpTest.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("jvm_"));
    }

    @Test void outboundClientPropagatesTraceparentAndRequestIdAndHonorsSentinelGovernance() throws Exception {
        AtomicReference<String> seenTraceparent = new AtomicReference<>();
        AtomicReference<String> seenRequestId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/auth/service-tokens", exchange -> {
            seenTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            seenRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] body = "{\"accessToken\":\"tok\",\"tokenType\":\"Bearer\",\"expiresIn\":120,\"audience\":\"knode-user\",\"permissions\":[\"users:public:read\"]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/internal/v1/users/public-profiles:batch", exchange -> {
            byte[] body = "{\"items\":[{\"userId\":\"42\",\"status\":\"FOUND\",\"nickName\":\"Bob\",\"avatar\":null,\"profileRevision\":\"1\",\"updatedAt\":\"2026-11-20T00:00:00Z\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            InMemorySpanExporter exporter = InMemorySpanExporter.create();
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
            OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .build();
            GovernedCall governed = new GovernedCall();
            UserProfileClient client = new UserProfileClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "knode-content",
                    "synthetic-secret",
                    singleProvider(governed),
                    singleProvider(otel));

            var span = otel.getTracer("test").spanBuilder("content-outbound-test").startSpan();
            MDC.put("requestId", "req-g9-trace-001");
            try (var ignored = span.makeCurrent()) {
                var result = client.lookup(List.of("42"));
                assertEquals("FOUND", result.get("42").status());
                assertEquals("Bob", result.get("42").nickName());
            } finally {
                span.end();
                MDC.remove("requestId");
            }
            assertEquals("req-g9-trace-001", seenRequestId.get());
            assertNotNull(seenTraceparent.get());
            assertTrue(seenTraceparent.get().startsWith("00-" + span.getSpanContext().getTraceId() + "-"));

            // Now block the outbound resource with a 0-QPS Sentinel FlowRule -> clean UNAVAILABLE degradation!
            FlowRule blockRule = new FlowRule("feign:knode-user#serviceTokens");
            blockRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            blockRule.setCount(0);
            FlowRuleManager.loadRules(List.of(blockRule));
            var blocked = client.lookup(List.of("42"));
            assertEquals("UNAVAILABLE", blocked.get("42").status());
            tracerProvider.close();
        } finally {
            server.stop(0);
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("token", token).GET().build();
        return AuthenticationHttpTest.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String sign(String kid, KeyPair pair) {
        Instant now = Instant.now();
        return Jwts.builder().header().keyId(kid).and().issuer("knode-user").subject("9007199254740993")
                .audience().add("knode-api").and().claim("sid", AuthenticationHttpTest.SID)
                .claim("scope", "blog").claim("token_use", "user").id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(5))).expiration(Date.from(now.plusSeconds(300)))
                .signWith(pair.getPrivate()).compact();
    }

    private static <T> ObjectProvider<T> singleProvider(T instance) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return instance; }
            @Override public T getIfAvailable() { return instance; }
            @Override public T getIfUnique() { return instance; }
            @Override public T getObject() { return instance; }
        };
    }

    private static KeyPair genKeys() {
        try { KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Path writePublicKey(KeyPair pair) {
        try {
            Path path = Files.createTempFile("knode-content-rot-", ".pub");
            Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            path.toFile().deleteOnExit();
            return path;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
