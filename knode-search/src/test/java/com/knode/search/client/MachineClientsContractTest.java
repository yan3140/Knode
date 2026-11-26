package com.knode.search.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.config.SearchClientsProperties;
import com.knode.search.model.QueryBudget;
import com.knode.search.support.LoopbackContentVisibilityStub;
import com.knode.search.support.LoopbackUserTokenStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MachineClientsContractTest {
    private LoopbackUserTokenStub userStub;
    private LoopbackContentVisibilityStub contentStub;
    private HttpServiceTokenClient tokenClient;
    private HttpContentVisibilityClient visibilityClient;

    @BeforeEach
    void setUp() throws Exception {
        userStub = new LoopbackUserTokenStub();
        contentStub = new LoopbackContentVisibilityStub();

        SearchClientsProperties props = new SearchClientsProperties();
        props.setUserOrigin(userStub.baseUrl());
        props.setContentOrigin(contentStub.baseUrl());
        props.setServiceId("knode-search");
        props.setBootstrapSecret("test-bootstrap-secret");
        props.setConnectTimeoutMs(300L);
        props.setUserTokenTimeoutMs(300L);
        props.setContentVisibilityTimeoutMs(300L);
        props.setTokenRefreshSkewSeconds(10L);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(300))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null))
                .build();
        ObjectMapper mapper = new ObjectMapper();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-26T06:00:00Z"), ZoneOffset.UTC);

        tokenClient = new HttpServiceTokenClient(httpClient, mapper, props, fixedClock);
        visibilityClient = new HttpContentVisibilityClient(httpClient, mapper, props, tokenClient);
    }

    @AfterEach
    void tearDown() {
        if (contentStub != null) {
            contentStub.close();
        }
        if (userStub != null) {
            userStub.close();
        }
    }

    @Test
    void acquiresCachesAndValidatesServiceTokenAndCallsVisibilityBatch() {
        contentStub.setDecision("101", "7", "PUBLIC", "READABLE");
        QueryBudget budget = QueryBudget.start(Duration.ofSeconds(2));

        Map<String, VisibilityDecision> first = visibilityClient.checkPublicSearch(List.of("101"), budget, "req-1");
        Map<String, VisibilityDecision> second = visibilityClient.checkPublicSearch(List.of("101"), budget, "req-2");

        assertThat(first.get("101").isPublicReadableMatchingRevision("7")).isTrue();
        assertThat(second.get("101").isPublicReadableMatchingRevision("7")).isTrue();

        assertThat(userStub.requests()).hasSize(1);
        LoopbackUserTokenStub.RecordedRequest tokenReq = userStub.requests().get(0);
        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString(
                "knode-search:test-bootstrap-secret".getBytes(StandardCharsets.UTF_8));
        assertThat(tokenReq.authorization()).isEqualTo(expectedBasic);
        assertThat(tokenReq.requestId()).isEqualTo("req-1");
        assertThat(tokenReq.body()).contains("\"audience\":\"knode-content\"");
        assertThat(tokenReq.body()).contains("\"content:visibility:read\"");

        assertThat(contentStub.requests()).hasSize(2);
        assertThat(contentStub.requests().get(0).authorization()).isEqualTo("Bearer svc-token-1");
        assertThat(contentStub.requests().get(0).body()).contains("\"purpose\":\"PUBLIC_SEARCH\"");
    }

    @Test
    void rejectsInvalidServiceTokenDtos() {
        QueryBudget budget = QueryBudget.start(Duration.ofSeconds(2));

        userStub.setHandler(req -> LoopbackUserTokenStub.StubResponse.ok(
                "{\"accessToken\":\"t1\",\"tokenType\":\"Bearer\",\"expiresIn\":120,"
                        + "\"audience\":\"wrong-aud\",\"permissions\":[\"content:visibility:read\"]}"));
        assertThatThrownBy(() -> tokenClient.acquireToken("knode-content", List.of("content:visibility:read"), budget, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Audience mismatch");

        userStub.setHandler(req -> LoopbackUserTokenStub.StubResponse.ok(
                "{\"accessToken\":\"t1\",\"tokenType\":\"Bearer\",\"expiresIn\":120,"
                        + "\"audience\":\"knode-content\",\"permissions\":[\"content:snapshot:read\"]}"));
        assertThatThrownBy(() -> tokenClient.acquireToken("knode-content", List.of("content:visibility:read"), budget, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Granted permissions");

        userStub.setHandler(req -> LoopbackUserTokenStub.StubResponse.ok(
                "{\"accessToken\":\"t1\",\"tokenType\":\"Bearer\",\"expiresIn\":301,"
                        + "\"audience\":\"knode-content\",\"permissions\":[\"content:visibility:read\"]}"));
        assertThatThrownBy(() -> tokenClient.acquireToken("knode-content", List.of("content:visibility:read"), budget, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiresIn");

        userStub.setHandler(req -> LoopbackUserTokenStub.StubResponse.ok(
                "{\"accessToken\":\"t1\",\"tokenType\":\"Bearer\",\"expiresIn\":120,"
                        + "\"audience\":\"knode-content\",\"permissions\":[\"content:visibility:read\"],\"extra\":1}"));
        assertThatThrownBy(() -> tokenClient.acquireToken("knode-content", List.of("content:visibility:read"), budget, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected field");
    }

    @Test
    void rejectsMissingOrCorruptVisibilityItemsAndInvalidatesTokenOn401() {
        QueryBudget budget = QueryBudget.start(Duration.ofSeconds(2));

        contentStub.setOverrideHandler(req -> LoopbackContentVisibilityStub.StubResponse.ok(
                "{\"items\":[{\"articleId\":\"101\",\"sourceRevision\":\"7\",\"visibility\":\"PUBLIC\",\"decision\":\"READABLE\"}]}"));
        assertThatThrownBy(() -> visibilityClient.checkPublicSearch(List.of("101", "102"), budget, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing requested article IDs");

        contentStub.setOverrideHandler(req -> LoopbackContentVisibilityStub.StubResponse.ok(
                "{\"items\":[{\"articleId\":\"101\",\"sourceRevision\":\"8\",\"visibility\":\"WITHDRAWN\",\"decision\":\"READABLE\"}]}"));
        assertThatThrownBy(() -> visibilityClient.checkPublicSearch(List.of("101"), budget, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READABLE decision requires PUBLIC");

        contentStub.setOverrideHandler(req -> LoopbackContentVisibilityStub.StubResponse.status(401, "{\"error\":\"INVALID_TOKEN\"}"));
        assertThatThrownBy(() -> visibilityClient.checkPublicSearch(List.of("101"), budget, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");

        // After 401 invalidation, next call must re-fetch a fresh token from User service.
        contentStub.clearOverrideHandler();
        contentStub.setDecision("101", "7", "PUBLIC", "READABLE");
        Map<String, VisibilityDecision> recovered = visibilityClient.checkPublicSearch(List.of("101"), budget, null);
        assertThat(recovered.get("101").isPublicReadableMatchingRevision("7")).isTrue();
        assertThat(userStub.requests()).hasSize(2);
    }
}
