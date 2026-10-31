package com.knode.governance;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import feign.Feign;
import feign.Request;
import feign.RequestLine;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = GovernanceHttpTest.TestApp.class,
    properties = {"spring.application.name=infra-test", "knode.governance.enabled=true"})
class GovernanceHttpTest {
    @LocalServerPort int port;
    @Autowired Endpoint endpoint;
    @Autowired GovernanceRules source;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void awaitInitialRules() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(source.health().getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UP));
    }

    @AfterEach void clear() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
        endpoint.fail.set(false); endpoint.calls.set(0);
    }

    @Test void routeTemplateBlocksAndRecoversThroughRealHttp() throws Exception {
        FlowRuleManager.loadRules(List.of(new FlowRule("HTTP GET /hello/{id}").setGrade(1).setCount(0)));
        assertThat(get("/hello/123").statusCode()).isEqualTo(429);
        FlowRuleManager.loadRules(List.of());
        HttpResponse<String> result = get("/hello/456");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).isEqualTo("hello");
    }

    @Test void feignDependencyFailureOpensCircuitThenRecovers() throws Exception {
        DegradeRule rule = new DegradeRule("feign:demo#fetch").setGrade(2).setCount(0)
            .setTimeWindow(1).setMinRequestAmount(1).setStatIntervalMs(1000);
        DegradeRuleManager.loadRules(List.of(rule));
        endpoint.fail.set(true);
        assertThat(get("/call").statusCode()).isEqualTo(503);
        assertThat(endpoint.calls.get()).isEqualTo(1);
        endpoint.fail.set(false);
        assertThat(get("/call").statusCode()).isEqualTo(503);
        assertThat(endpoint.calls.get()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(6)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
            assertThat(get("/call").statusCode()).isEqualTo(200));
        assertThat(endpoint.calls.get()).isGreaterThan(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    interface Dependency {
        @RequestLine("GET /dependency") String fetch();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Endpoint.class)
    static class TestApp {
        @Bean(name = "knodeGovernanceConfig", destroyMethod = "")
        ConfigService fakeNacos() throws Exception {
            ConfigService config = mock(ConfigService.class);
            when(config.getServerStatus()).thenReturn("UP");
            when(config.getConfigAndSignListener(anyString(), anyString(), anyLong(), any(Listener.class))).thenReturn("[]");
            return config;
        }
    }

    @RestController
    static class Endpoint {
        final AtomicBoolean fail = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        final GovernedCall governed;
        Endpoint(GovernedCall governed) { this.governed = governed; }

        @GetMapping("/hello/{id}") String hello(@PathVariable String id) { return "hello"; }
        @GetMapping("/dependency") String dependency() {
            calls.incrementAndGet();
            if (fail.get()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            return "ok";
        }
        @GetMapping("/call") String call(HttpServletRequest request) {
            Dependency dependency = Feign.builder().options(new Request.Options(1, TimeUnit.SECONDS, 1, TimeUnit.SECONDS, true))
                .target(Dependency.class, "http://127.0.0.1:" + request.getLocalPort());
            return governed.execute("feign:demo#fetch", dependency::fetch);
        }
    }
}
