package com.knode.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

@AutoConfiguration
@ConditionalOnClass(HttpServletRequest.class)
@ConditionalOnProperty(name = "knode.observability.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServletObservationConfiguration {
    private static final String ATTRIBUTE = ServletObservationConfiguration.class.getName() + ".requestId";

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> servletRequestId() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>();
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setFilter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                var ids = Collections.list(request.getHeaders("X-Request-Id"));
                String id = RequestCorrelation.validatedOrNew(ids.size() == 1 ? ids.get(0) : null);
                request.setAttribute(ATTRIBUTE, id);
                response.setHeader("X-Request-Id", id);
                String previous = MDC.get("requestId");
                MDC.put("requestId", id);
                try { chain.doFilter(request, response); }
                finally { if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous); }
            }
        });
        return registration;
    }

    @Bean
    ObservationFilter servletRequestCorrelation() {
        return context -> {
            if (context instanceof ServerRequestObservationContext http && http.getResponse() != null) {
                String id = http.getResponse().getHeader("X-Request-Id");
                if (RequestCorrelation.valid(id)) context.addHighCardinalityKeyValue(KeyValue.of("knode.request_id", id));
            }
            return context;
        };
    }

    /** Only Boot-managed RestClient builders carry the current validated request ID. */
    @Bean
    RestClientCustomizer requestIdRestClientCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                Object id = attributes.getRequest().getAttribute(ATTRIBUTE);
                if (id instanceof String value && RequestCorrelation.valid(value))
                    request.getHeaders().set("X-Request-Id", value);
            }
            return execution.execute(request, body);
        });
    }
}
