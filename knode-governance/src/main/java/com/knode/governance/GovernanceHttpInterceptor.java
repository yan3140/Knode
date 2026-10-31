package com.knode.governance;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

final class GovernanceHttpInterceptor implements AsyncHandlerInterceptor {
    private static final String ENTRY = GovernanceHttpInterceptor.class.getName() + ".entry";
    private static final String ASYNC = GovernanceHttpInterceptor.class.getName() + ".async";

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod) || request.getAttribute(ASYNC) != null) return true;
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) return true;
        String resource = "HTTP " + request.getMethod() + " " + pattern;
        try {
            request.setAttribute(ENTRY, SphU.entry(resource, EntryType.IN));
            return true;
        } catch (BlockException blocked) {
            int status = blocked instanceof DegradeException ? 503 : 429;
            response.setStatus(status);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":" + status + ",\"msg\":\"Request blocked\"}");
            return false;
        }
    }

    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        exit(request, ex != null ? ex : response.getStatus() >= 500 ? new IllegalStateException("HTTP failure") : null);
    }

    @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ASYNC, Boolean.TRUE);
        exit(request, null);
    }

    private void exit(HttpServletRequest request, Exception failure) {
        Entry entry = (Entry) request.getAttribute(ENTRY);
        if (entry != null) {
            request.removeAttribute(ENTRY);
            if (failure != null) Tracer.traceEntry(failure, entry);
            entry.exit();
        }
    }
}
