package com.yan.admin.filter;

import com.yan.admin.config.AgentInternalSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AgentServiceAuthenticationFilter extends OncePerRequestFilter {

    public static final String SERVICE_KEY_HEADER = "X-Agent-Service-Key";
    public static final String AGENT_SERVICE_AUTHORITY = "agent:internal";
    private static final String INTERNAL_KNOWLEDGE_PATH = "/internal/api/knowledge/";

    private final AgentInternalSecurityProperties properties;

    public AgentServiceAuthenticationFilter(AgentInternalSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_KNOWLEDGE_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "Agent integration is disabled");
            return;
        }
        if (!matches(request.getHeader(SERVICE_KEY_HEADER), properties.getServiceKey())) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid Agent service identity");
            return;
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "yan-agent",
                        null,
                        List.of(new SimpleGrantedAuthority(AGENT_SERVICE_AUTHORITY)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean matches(String supplied, String expected) {
        if (supplied == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
