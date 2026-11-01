package com.knode.observability;

import io.opentelemetry.api.OpenTelemetry;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(name = "knode.observability.enabled", havingValue = "true")
public class ObservabilityAutoConfiguration {
    @Bean
    EventTraceContext eventTraceContext(OpenTelemetry telemetry,
            @Value("${spring.application.name:}") String application,
            @Value("${knode.observability.environment:}") String environment,
            @Value("${knode.observability.version:}") String version,
            @Value("${management.otlp.tracing.endpoint:}") String endpoint,
            @Value("${management.tracing.enabled:false}") boolean tracingEnabled) {
        if (!tracingEnabled || application.isBlank() || environment.isBlank() || version.isBlank() || endpoint.isBlank())
            throw new IllegalStateException("Explicit service name, environment, version and OTLP endpoint are required");
        URI uri = URI.create(endpoint);
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "::1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getFragment() != null || uri.getQuery() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || (loopback && "http".equalsIgnoreCase(uri.getScheme()))))
            throw new IllegalStateException("OTLP requires HTTPS except for a loopback HTTP endpoint");
        return new EventTraceContext(telemetry);
    }
}
