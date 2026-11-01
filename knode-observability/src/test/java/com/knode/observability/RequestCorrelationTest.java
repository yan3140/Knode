package com.knode.observability;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationTest {
    @Test void servletRegeneratesInvalidIdAndKeepsItOutOfMetrics() throws Exception {
        var config = new ServletObservationConfiguration();
        var filter = config.servletRequestId().getFilter();
        var req = new MockHttpServletRequest(); req.addHeader("X-Request-Id", "bad value!");
        var response = new MockHttpServletResponse();
        MDC.put("requestId", "prior");
        try { filter.doFilter(req, response, new MockFilterChain()); assertThat(MDC.get("requestId")).isEqualTo("prior"); }
        finally { MDC.remove("requestId"); }
        String id = response.getHeader("X-Request-Id");
        assertThat(RequestCorrelation.valid(id)).isTrue();
        var context = new org.springframework.http.server.observation.ServerRequestObservationContext(req, response);
        config.servletRequestCorrelation().map(context);
        assertThat(context.getHighCardinalityKeyValue("knode.request_id").getValue()).isEqualTo(id);
        assertThat(context.getLowCardinalityKeyValue("knode.request_id")).isNull();
    }

    @Test void reactiveUsesOnlyValidatedResponseIdAsTraceAttribute() {
        var response = new MockServerHttpResponse(); response.getHeaders().set("X-Request-Id", "req-123");
        var context = new ServerRequestObservationContext(MockServerHttpRequest.get("/api/test/123").build(), response, Map.of());
        new ReactiveObservationConfiguration().gatewayRequestCorrelation().map(context);
        assertThat(context.getHighCardinalityKeyValue("knode.request_id").getValue()).isEqualTo("req-123");
        assertThat(context.getLowCardinalityKeyValue("knode.request_id")).isNull();
    }
}
