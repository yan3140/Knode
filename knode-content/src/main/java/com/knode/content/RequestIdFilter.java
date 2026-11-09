package com.knode.content;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        List<String> values = Collections.list(req.getHeaders("X-Request-Id"));
        String id = values.size() == 1 && values.get(0).matches("[A-Za-z0-9._-]{1,64}")
                ? values.get(0) : UUID.randomUUID().toString();
        resp.setHeader("X-Request-Id", id);
        req.setAttribute("knode.requestId", id);
        MDC.put("requestId", id);
        try { chain.doFilter(req, resp); } finally { MDC.remove("requestId"); }
    }
}
