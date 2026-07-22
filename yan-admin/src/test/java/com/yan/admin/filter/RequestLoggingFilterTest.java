package com.yan.admin.filter;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {

    @Test
    void shouldReuseSafeRequestIdAndClearMdcAfterRequest() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter(500);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/blog/article/1");
        request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "load-test-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isEqualTo("load-test-42");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void shouldReplaceUnsafeRequestId() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter(500);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/blog/search");
        request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "invalid id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER))
                .isNotBlank()
                .isNotEqualTo("invalid id with spaces");
    }
}
