package com.knode.gateway;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.discovery.enabled=false"})
class GatewayIntegrationTest {
    static final AtomicInteger CONTENT_CALLS = new AtomicInteger();
    static final AtomicInteger SEARCH_CALLS = new AtomicInteger();
    static final DisposableServer CONTENT = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
        CONTENT_CALLS.incrementAndGet();
        String value = req.uri() + "|" + req.requestHeaders().get("token") + "|" +
                req.requestHeaders().get("X-User-Id") + "|" + req.requestHeaders().get("X-Request-Id");
        return res.sendString(reactor.core.publisher.Mono.just(value));
    }).bindNow();
    static final DisposableServer SEARCH = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
        SEARCH_CALLS.incrementAndGet();
        if (req.uri().contains("fail=yes")) return res.status(503).sendString(reactor.core.publisher.Mono.just("unavailable"));
        return res.sendString(req.receive().aggregate().asString().map(body -> req.uri() + "|" + body + "|" + req.requestHeaders().get("X-User-Id")));
    }).bindNow();
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry r) {
        r.add("knode.gateway.content-uri", () -> "http://127.0.0.1:" + CONTENT.port());
        r.add("knode.gateway.search-uri", () -> "http://127.0.0.1:" + SEARCH.port());
        r.add("knode.gateway.user-uri", () -> "http://127.0.0.1:" + SEARCH.port());
    }
    @AfterAll static void stop() { CONTENT.disposeNow(); SEARCH.disposeNow(); }

    @LocalServerPort int port;
    @Autowired RouteLocator routes;
    @MockitoBean AccessGate gate;
    @BeforeEach void failClosedByDefault() { when(gate.allowed(any())).thenCallRealMethod(); }
    WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build(); }

    @Test void profileActuallyForwardsWithoutTrustingForgedIdentity() {
        when(gate.allowed(any())).thenReturn(true);
        client().get().uri("/api/blog/user/profile/42?q=hello")
                .header("token", "sample-token").header("X-User-Id", "forged")
                .header("X-Request-Id", "request-1").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "request-1")
                .expectBody(String.class).isEqualTo("/api/blog/user/profile/42?q=hello|sample-token|null|request-1");
    }

    @Test void sentinelBlocksRouteWithHttp429() {
        when(gate.allowed(any())).thenReturn(true);
        com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager.loadRules(
                java.util.Set.of(new com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule("profile-content").setCount(0)));
        int before = CONTENT_CALLS.get();
        try {
            client().get().uri("/api/blog/user/profile/42").exchange().expectStatus().isEqualTo(429);
            assertThat(CONTENT_CALLS.get()).isEqualTo(before);
        } finally {
            com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager.loadRules(java.util.Set.of());
        }
    }

    @Test void nacosPayloadApiGroupActuallyBlocksRoute() {
        when(gate.allowed(any())).thenReturn(true);
        NacosGatewayRules source = new NacosGatewayRules(
                org.mockito.Mockito.mock(com.alibaba.nacos.api.config.ConfigService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), "KNODE_SENTINEL");
        source.apply("knode-gateway.gw-api-group.json",
                "[{\"apiName\":\"profile-api\",\"predicateItems\":[{\"pattern\":\"/api/blog/user/profile/**\",\"matchStrategy\":1}]}]");
        source.apply("knode-gateway.gw-flow.json",
                "[{\"resource\":\"profile-api\",\"resourceMode\":1,\"count\":0}]");
        int before = CONTENT_CALLS.get();
        try {
            client().get().uri("/api/blog/user/profile/42").exchange().expectStatus().isEqualTo(429);
            assertThat(CONTENT_CALLS.get()).isEqualTo(before);
        } finally {
            com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager.loadRules(java.util.Set.of());
            com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager.loadApiDefinitions(java.util.Set.of());
        }
    }

    @Test void routeOrderSelectsPreciseRules() {
        assertThat(routeFor("/api/blog/user/profile/42")).isEqualTo("profile-content");
        assertThat(routeFor("/api/admin/content/search/rebuild")).isEqualTo("admin-search");
    }

    String routeFor(String path) {
        return routes.getRoutes().filterWhen(route -> route.getPredicate().apply(
                MockServerWebExchange.from(MockServerHttpRequest.get(path).build())))
                .next().map(route -> route.getId()).block();
    }

    @Test void matrixResolvesAllServices() {
        assertThat(routeFor("/api/blog/auth/login")).isEqualTo("user");
        assertThat(routeFor("/api/admin/system/role/list")).isEqualTo("user");
        assertThat(routeFor("/api/blog/article/42")).isEqualTo("blog-content");
        assertThat(routeFor("/api/admin/upload")).isEqualTo("admin-content");
        assertThat(routeFor("/api/admin/ops/outbox/list")).isEqualTo("admin-content");
        assertThat(routeFor("/api/blog/follow/42")).isEqualTo("community");
        assertThat(routeFor("/api/blog/search")).isEqualTo("search");
        assertThat(routeFor("/api/blog/knowledge/5")).isEqualTo("knowledge");
    }

    @Test void encodedOrdinaryPathAndInternalSubstringForwardUnchanged() {
        when(gate.allowed(any())).thenReturn(true);
        client().get().uri(java.net.URI.create("http://127.0.0.1:" + port + "/api/blog/user/profile/internalized/%E4%B8%AD?q=%E4%B8%AD"))
                .exchange().expectStatus().isOk().expectBody(String.class)
                .value(value -> assertThat(value).startsWith("/api/blog/user/profile/internalized/%E4%B8%AD?q=%E4%B8%AD|"));
    }

    @Test void suspiciousPathFormsDoNotReachBackend() {
        when(gate.allowed(any())).thenReturn(true);
        int before = CONTENT_CALLS.get();
        for (String path : java.util.List.of("/api/blog/user/profile/%69nternal/x",
                "/api/blog/user/profile/%2e%2e/internal/x", "/api/blog/user/profile/%2Finternal/x",
                "/api/blog/user/profile/%5Cinternal/x", "/api/blog/user/profile/%2569nternal/x",
                "/api/blog/user/profile/internal;foo/x")) {
            client().get().uri(java.net.URI.create("http://127.0.0.1:" + port + path)).exchange().expectStatus().isForbidden();
        }
        assertThat(CONTENT_CALLS.get()).isEqualTo(before);
    }

    @Test void searchWriteActuallyForwardsOnlyWithTestAuthenticationStub() {
        when(gate.allowed(any())).thenReturn(true);
        client().post().uri("/api/admin/content/search/rebuild?q=hello").header("X-User-Id", "forged")
                .bodyValue("sample-body").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("/api/admin/content/search/rebuild?q=hello|sample-body|null");
    }

    @Test void failedNonIdempotentWriteIsNotRetried() {
        when(gate.allowed(any())).thenReturn(true);
        int before = SEARCH_CALLS.get();
        client().post().uri("/api/admin/content/search/rebuild?fail=yes").bodyValue("sample-body")
                .exchange().expectStatus().isEqualTo(503);
        assertThat(SEARCH_CALLS.get()).isEqualTo(before + 1);
    }

    @Test void protectedWriteAndInternalPathNeverReachBackend() {
        int contentBefore = CONTENT_CALLS.get();
        client().get().uri("/api/blog/user/profile/42").exchange().expectStatus().isEqualTo(503);
        assertThat(CONTENT_CALLS.get()).isEqualTo(contentBefore);
        int before = SEARCH_CALLS.get();
        client().post().uri("/api/admin/content/search/rebuild").bodyValue("example")
                .exchange().expectStatus().isEqualTo(503);
        client().get().uri("/internal/v1/secret").exchange().expectStatus().isForbidden();
        assertThat(SEARCH_CALLS.get()).isEqualTo(before);
    }

    @Test void duplicateTokenRejectedAndHealthAvailable() {
        client().get().uri("/api/blog/user/profile/42").header("token", "one", "two")
                .exchange().expectStatus().isBadRequest();
        client().get().uri("/api/blog/user/profile/42").header("Authorization", "one", "two")
                .exchange().expectStatus().isBadRequest();
        client().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client().get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(503);
    }
}
