package com.knode.observability;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
            .withBean(OpenTelemetry.class, OpenTelemetry::noop);

    @Test void disabledByDefaultAndMissingConfigurationFails() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(EventTraceContext.class));
        runner.withPropertyValues("knode.observability.enabled=true").run(ctx ->
                assertThat(ctx).hasFailed());
    }

    @Test void onlyLoopbackMayUseHttp() {
        var configured = runner.withPropertyValues("knode.observability.enabled=true", "spring.application.name=demo",
                "knode.observability.environment=test", "knode.observability.version=1", "management.tracing.enabled=true");
        configured.withPropertyValues("management.otlp.tracing.endpoint=http://example.com:4318/v1/traces")
                .run(ctx -> assertThat(ctx).hasFailed());
        configured.withPropertyValues("management.otlp.tracing.endpoint=http://127.0.0.1:4318/v1/traces")
                .run(ctx -> assertThat(ctx).hasSingleBean(EventTraceContext.class));
        configured.withPropertyValues("management.otlp.tracing.endpoint=https://collector.example/v1/traces")
                .run(ctx -> assertThat(ctx).hasSingleBean(EventTraceContext.class));
    }
}
