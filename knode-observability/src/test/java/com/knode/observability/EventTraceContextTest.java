package com.knode.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EventTraceContextTest {
    @Test void storedProducerContextSurvivesRelayAndRetriesWithoutAdoptingRelay() {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        try {
            var api = new EventTraceContext(sdk);
            Span producer = sdk.getTracer("test").spanBuilder("producer").startSpan();
            EventTraceContext.Saved saved;
            try (Scope ignored = producer.makeCurrent()) { saved = api.captureCurrent().orElseThrow(); }
            producer.end();
            Span relay = sdk.getTracer("test").spanBuilder("relay").startSpan();
            try (Scope ignored = relay.makeCurrent()) {
                Span first = api.startConsumer(api.fromStored(saved.traceparent(), saved.tracestate()).orElseThrow(), "session.consume", 1);
                first.end();
                Span retry = api.startConsumer(saved, "session.consume", 2);
                retry.end();
                Span missing = api.startConsumer(null, "session.consume", 1);
                missing.end();
            }
            relay.end();
            var spans = exporter.getFinishedSpanItems();
            SpanData first = spans.stream().filter(x -> x.getName().equals("session.consume")
                    && x.getAttributes().get(AttributeKey.longKey("knode.event.delivery_attempt")) == 1L
                    && x.getParentSpanId().equals(producer.getSpanContext().getSpanId())).findFirst().orElseThrow();
            assertThat(first.getKind()).isEqualTo(SpanKind.CONSUMER);
            assertThat(first.getTraceId()).isEqualTo(producer.getSpanContext().getTraceId());
            assertThat(spans.stream().filter(x -> x.getName().equals("session.consume"))
                    .map(SpanData::getParentSpanId)).contains(producer.getSpanContext().getSpanId(), "0000000000000000")
                    .doesNotContain(relay.getSpanContext().getSpanId());
            assertThat(spans.stream().filter(x -> x.getName().equals("session.consume"))
                    .map(x -> x.getAttributes().get(AttributeKey.longKey("knode.event.delivery_attempt")))).contains(1L, 2L);
        } finally { provider.close(); }
    }

    @Test void malformedAndOversizedCarrierNeverBecomesAParent() {
        var api = new EventTraceContext(OpenTelemetrySdk.builder().build());
        assertThat(api.fromStored("bad", null)).isEmpty();
        assertThat(api.fromStored("00-00000000000000000000000000000000-0000000000000000-01", null)).isEmpty();
        assertThat(api.fromStored("x".repeat(513), null)).isEmpty();
        String valid = "00-12345678901234567890123456789012-1234567890123456-01";
        assertThat(api.fromStored(valid, "bad state")).isEmpty();
        assertThat(api.fromStored(valid, "vendor=value")).isEmpty();
        assertThat(api.fromStored(valid, null)).isPresent();
        assertThatThrownBy(() -> api.startConsumer(null, "contains private text", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.startConsumer(null, "consume", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
