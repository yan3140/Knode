package com.knode.community;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.knode.community.client.CommunityUserClient;
import com.knode.community.client.ContentNotificationMetadataClient;
import com.knode.community.consumer.ContentEventIngestor;
import com.knode.community.runtime.CommunityDiscoveryStatus;
import com.knode.community.runtime.CommunityGovernanceStatus;
import com.knode.governance.GovernedCall;
import com.knode.observability.EventTraceContext;
import com.sun.net.httpserver.HttpServer;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"KNODE_MANAGEMENT_PORT=0", "KNODE_COMMUNITY_PORT=0"})
@AutoConfigureObservability
@Import(AuthenticationAndConfigTest.TestFixtures.class)
class CommunityRuntimeWiringTest {
    @LocalManagementPort int managementPort;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager txManager;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry p) {
        AuthenticationAndConfigTest.registerBaseProperties(p, "community_runtime_test");
    }

    @BeforeEach
    void reset() {
        FlowRuleManager.loadRules(List.of());
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("db/community-schema.sql")).execute(db.getDataSource());
    }

    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void prometheusAndDiscoveryGovernanceHealthIndicatorsBehaveFailClosed() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                .GET().build();
        HttpResponse<String> resp = AuthenticationAndConfigTest.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("jvm_"));

        MockEnvironment disabledEnv = new MockEnvironment();
        assertEquals(Status.UP, new CommunityDiscoveryStatus(disabledEnv, nullProvider(), nullProvider()).health().getStatus());
        assertEquals(Status.UP, new CommunityGovernanceStatus(disabledEnv, nullProvider()).health().getStatus());

        MockEnvironment enabledEnv = new MockEnvironment()
                .withProperty("spring.cloud.nacos.discovery.enabled", "true")
                .withProperty("knode.governance.enabled", "true");
        assertEquals(Status.OUT_OF_SERVICE, new CommunityDiscoveryStatus(enabledEnv, nullProvider(), nullProvider()).health().getStatus());
        assertEquals(Status.OUT_OF_SERVICE, new CommunityGovernanceStatus(enabledEnv, nullProvider()).health().getStatus());
    }

    @Test
    void outboundClientsAndStreamIngestorPropagateW3CTraceAndHonorSentinelFlowRules() throws Exception {
        AtomicReference<String> userTraceparent = new AtomicReference<>();
        AtomicReference<String> userReqId = new AtomicReference<>();
        AtomicReference<String> contentTraceparent = new AtomicReference<>();
        AtomicReference<String> contentReqId = new AtomicReference<>();
        List<String> validityTraceparents = new CopyOnWriteArrayList<>();
        List<String> validityRequestIds = new CopyOnWriteArrayList<>();
        ObjectMapper json = new ObjectMapper();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/auth/service-tokens", exchange -> {
            userTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            userReqId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String aud = reqBody.contains("knode-content") ? "knode-content" : "knode-user";
            String perm = reqBody.contains("knode-content") ? "content:notification-metadata:read"
                    : reqBody.contains("users:state:read") ? "users:state:read" : "users:public:read";
            byte[] body = ("{\"accessToken\":\"tok-" + aud + "\",\"tokenType\":\"Bearer\",\"expiresIn\":120,"
                    + "\"audience\":\"" + aud + "\",\"permissions\":[\"" + perm + "\"]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/internal/v1/users/validity:batch", exchange -> {
            validityTraceparents.add(exchange.getRequestHeaders().getFirst("traceparent"));
            validityRequestIds.add(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            List<Map<String, Object>> items = new ArrayList<>();
            json.readTree(exchange.getRequestBody()).path("userIds").forEach(id ->
                    items.add(Map.of("userId", id.asText(), "exists", true, "active", true)));
            byte[] body = json.writeValueAsBytes(Map.of("items", items));
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/internal/v1/content/articles/notification-metadata:batch", exchange -> {
            contentTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            contentReqId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] body = "{\"items\":[{\"articleId\":\"101\",\"sourceRevision\":\"2\",\"visibility\":\"PUBLIC\",\"decision\":\"READABLE\",\"title\":\"Live Title\"}]}"
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
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();

            try (CommunityUserClient userClient = new CommunityUserClient(
                    origin, "knode-community", "synthetic-secret", Clock.systemUTC(),
                    singleProvider(governed), singleProvider((OpenTelemetry) otel))) {
                ContentNotificationMetadataClient contentClient = new ContentNotificationMetadataClient(
                        origin, userClient, singleProvider(governed), singleProvider((OpenTelemetry) otel));

                List<String> ids = IntStream.rangeClosed(1, 101).mapToObj(Integer::toString).toList();
                var parentSpan = otel.getTracer("community-test").spanBuilder("community-http-inbound").startSpan();
                MDC.put("requestId", "req-comm-trace-001");
                try (var scope = parentSpan.makeCurrent()) {
                    var batch = contentClient.lookup(List.of("101"));
                    assertTrue(batch.available());
                    assertEquals("Live Title", batch.items().get("101").title());
                    assertEquals(101, userClient.lookupValidity(ids, System.nanoTime() + TimeUnit.SECONDS.toNanos(3))
                            .accounts().size());
                } finally {
                    parentSpan.end();
                    MDC.remove("requestId");
                }

                String expectedPrefix = "00-" + parentSpan.getSpanContext().getTraceId() + "-";
                assertEquals("req-comm-trace-001", userReqId.get());
                assertEquals("req-comm-trace-001", contentReqId.get());
                assertNotNull(userTraceparent.get());
                assertNotNull(contentTraceparent.get());
                assertTrue(userTraceparent.get().startsWith(expectedPrefix));
                assertTrue(contentTraceparent.get().startsWith(expectedPrefix));
                assertEquals(List.of("req-comm-trace-001", "req-comm-trace-001"), validityRequestIds);
                assertEquals(2, validityTraceparents.size());
                assertTrue(validityTraceparents.stream().allMatch(s -> s != null && s.startsWith(expectedPrefix)));

                FlowRule blockValidity = new FlowRule("feign:knode-user#validityBatch");
                blockValidity.setGrade(RuleConstant.FLOW_GRADE_QPS);
                blockValidity.setCount(0);
                FlowRuleManager.loadRules(List.of(blockValidity));
                assertFalse(userClient.lookupValidity(ids, System.nanoTime() + TimeUnit.SECONDS.toNanos(3)).available());
                assertEquals(2, validityRequestIds.size());

                // Block content metadata resource via 0-QPS Sentinel FlowRule -> degrades cleanly to unavailable()
                FlowRule blockContent = new FlowRule("feign:knode-content#notificationMetadata");
                blockContent.setGrade(RuleConstant.FLOW_GRADE_QPS);
                blockContent.setCount(0);
                FlowRuleManager.loadRules(List.of(blockContent));
                assertFalse(contentClient.lookup(List.of("101")).available());

                // Verify consumer EventTraceContext links/restores producer traceparent on stream ingest
                exporter.reset();
                EventTraceContext eventTraces = new EventTraceContext(otel);
                ContentEventIngestor tracedIngestor = new ContentEventIngestor(db, txManager, singleProvider(eventTraces));
                String producerTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
                String rawCounter = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"ArticleCounterChanged\","
                        + "\"schemaVersion\":1,\"producer\":\"knode-content\",\"aggregateType\":\"ARTICLE_COUNTER\","
                        + "\"aggregateId\":\"101\",\"sourceRevision\":\"1\",\"occurredAt\":\"2026-11-24T12:00:00Z\","
                        + "\"visibility\":\"PUBLIC\",\"traceContext\":{\"traceparent\":\"00-" + producerTraceId + "-00f067aa0ba902b7-01\"},"
                        + "\"payload\":{\"articleId\":\"101\",\"viewCount\":\"10\",\"counterRevision\":\"1\"}}";
                assertEquals(ContentEventIngestor.Outcome.IGNORED, tracedIngestor.ingest("1-0", rawCounter, 2).outcome());
                assertTrue(exporter.getFinishedSpanItems().stream()
                        .anyMatch(s -> "community.content_event.ingest".equals(s.getName())
                                && (producerTraceId.equals(s.getTraceId())
                                || s.getLinks().stream().anyMatch(l -> producerTraceId.equals(l.getSpanContext().getTraceId())))));
            } finally {
                tracerProvider.close();
            }
        } finally {
            server.stop(0);
        }
    }

    private static <T> ObjectProvider<T> singleProvider(T instance) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return instance; }
            @Override public T getIfAvailable() { return instance; }
            @Override public T getIfUnique() { return instance; }
            @Override public T getObject() { return instance; }
        };
    }

    private static <T> ObjectProvider<T> nullProvider() {
        return singleProvider(null);
    }
}
