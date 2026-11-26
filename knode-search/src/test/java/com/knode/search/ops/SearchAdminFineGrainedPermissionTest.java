package com.knode.search.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.auth.Session;
import com.knode.auth.UserTokenVerifier;
import com.knode.search.auth.AdminAuthorityClient;
import com.knode.search.auth.SearchAuthProperties;
import com.knode.search.auth.SearchSecurityFilter;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.support.TestJwtSigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchAdminFineGrainedPermissionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void configuredFineGrainedActionPermissionsGrantHolderAndRejectNonHolder() throws Exception {
        TestJwtSigner signer = TestJwtSigner.generate("knode-fg-k1");
        UserTokenVerifier userVerifier = new UserTokenVerifier(Map.of(signer.kid(), signer.publicKey()), Clock.systemUTC());
        ServiceTokenVerifier svcVerifier = new ServiceTokenVerifier(Map.of(signer.kid(), signer.publicKey()), Clock.systemUTC());

        String sid = UUID.randomUUID().toString();
        String token = signer.signUserToken("7", sid, "admin", 600);
        Session activeSession = new Session(1, sid, "7", "admin", "ACTIVE", Instant.now().plusSeconds(600).getEpochSecond());

        SearchAuthProperties props = new SearchAuthProperties();
        props.getAdmin().setSyncPermissions(List.of("search:admin:sync"));

        SearchSecurityFilter filter = new SearchSecurityFilter(
                userVerifier,
                svcVerifier,
                requestedSid -> Optional.of(activeSession),
                (rawToken, reqId) -> new AdminAuthorityClient.AdminAuthority("7", Set.of("search:admin:sync")),
                props,
                new SearchMetrics(new SimpleMeterRegistry()),
                MAPPER
        );

        // Holder of configured search:admin:sync is allowed on /sync/{id}
        MockHttpServletRequest syncReq = new MockHttpServletRequest("POST", "/api/admin/content/search/sync/100");
        syncReq.setServletPath("/api/admin/content/search/sync/100");
        syncReq.addHeader("token", token);
        MockHttpServletResponse syncResp = new MockHttpServletResponse();
        MockFilterChain syncChain = new MockFilterChain();
        filter.doFilter(syncReq, syncResp, syncChain);
        assertThat(syncResp.getStatus()).isEqualTo(200);
        assertThat(syncReq.getAttribute(SearchSecurityFilter.ATTR_ADMIN_PRINCIPAL)).isNotNull();

        // Same admin without rebuild permission stays fail-closed 403 on /rebuild
        MockHttpServletRequest rebuildReq = new MockHttpServletRequest("POST", "/api/admin/content/search/rebuild");
        rebuildReq.setServletPath("/api/admin/content/search/rebuild");
        rebuildReq.addHeader("token", token);
        MockHttpServletResponse rebuildResp = new MockHttpServletResponse();
        filter.doFilter(rebuildReq, rebuildResp, new MockFilterChain());
        assertThat(rebuildResp.getStatus()).isEqualTo(403);
    }

    @Test
    void unconfiguredAuthKeysFailClosedWith503OnProtectedRoutesWhilePassingPublicSearch() throws Exception {
        SearchSecurityFilter filter = new SearchSecurityFilter(
                null,
                null,
                sid -> Optional.empty(),
                (rawToken, reqId) -> new AdminAuthorityClient.AdminAuthority("1", Set.of("*:*:*")),
                new SearchAuthProperties(),
                new SearchMetrics(new SimpleMeterRegistry()),
                MAPPER
        );

        // Internal ops without configured verifier keys -> 503 AUTH_STATE_UNAVAILABLE
        MockHttpServletRequest opsReq = new MockHttpServletRequest("GET", "/internal/v1/ops/consumers/summary");
        opsReq.setServletPath("/internal/v1/ops/consumers/summary");
        opsReq.addHeader("Authorization", "Bearer dummy");
        MockHttpServletResponse opsResp = new MockHttpServletResponse();
        filter.doFilter(opsReq, opsResp, new MockFilterChain());
        assertThat(opsResp.getStatus()).isEqualTo(503);
        assertThat(MAPPER.readTree(opsResp.getContentAsString()).path("error").path("code").asText())
                .isEqualTo("AUTH_STATE_UNAVAILABLE");

        // Admin route with token header but unconfigured user verifier -> 503 code 500
        MockHttpServletRequest adminReq = new MockHttpServletRequest("POST", "/api/admin/content/search/rebuild");
        adminReq.setServletPath("/api/admin/content/search/rebuild");
        adminReq.addHeader("token", "dummy.jwt.token");
        MockHttpServletResponse adminResp = new MockHttpServletResponse();
        filter.doFilter(adminReq, adminResp, new MockFilterChain());
        assertThat(adminResp.getStatus()).isEqualTo(503);
        assertThat(MAPPER.readTree(adminResp.getContentAsString()).path("code").asInt()).isEqualTo(500);

        // Public search passes untouched
        MockHttpServletRequest pubReq = new MockHttpServletRequest("GET", "/api/blog/search");
        pubReq.setServletPath("/api/blog/search");
        MockHttpServletResponse pubResp = new MockHttpServletResponse();
        MockFilterChain pubChain = new MockFilterChain();
        filter.doFilter(pubReq, pubResp, pubChain);
        assertThat(pubChain.getRequest()).isSameAs(pubReq);
    }
}
