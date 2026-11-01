package com.knode.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Persist only the returned W3C values with an immutable event; never use relay-thread context. */
public final class EventTraceContext {
    private static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();
    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };
    private final Tracer tracer;

    public EventTraceContext(OpenTelemetry telemetry) {
        this.tracer = telemetry.getTracer("com.knode.observability.events", "1");
    }

    public Optional<Saved> captureCurrent() {
        Map<String, String> fields = new HashMap<>();
        W3C.inject(Context.current(), fields, SETTER);
        return fromStored(fields.get("traceparent"), null);
    }

    public Optional<Saved> fromStored(String traceparent, String tracestate) {
        // Tracestate is opaque and may contain sensitive vendor data. This version never persists it.
        if (traceparent == null || tracestate != null || traceparent.length() > 512) return Optional.empty();
        Map<String, String> fields = new HashMap<>();
        fields.put("traceparent", traceparent);
        Context extracted = W3C.extract(Context.root(), fields, GETTER);
        if (!Span.fromContext(extracted).getSpanContext().isValid()) return Optional.empty();
        Map<String, String> normalized = new HashMap<>();
        W3C.inject(extracted, normalized, SETTER);
        return Optional.of(new Saved(normalized.get("traceparent"), normalized.get("tracestate")));
    }

    /** Starts with the persisted producer as parent, or a new root if context is absent/invalid. */
    public Span startConsumer(Saved saved, String operation, int attempt) {
        if (operation == null || !operation.matches("[A-Za-z0-9._-]{1,80}") || attempt < 1)
            throw new IllegalArgumentException("Invalid consumer span arguments");
        Context parent = Context.root();
        if (saved != null) {
            Map<String, String> fields = new HashMap<>();
            fields.put("traceparent", saved.traceparent);
            if (saved.tracestate != null) fields.put("tracestate", saved.tracestate);
            parent = W3C.extract(Context.root(), fields, GETTER);
        }
        return tracer.spanBuilder(operation).setParent(parent).setSpanKind(SpanKind.CONSUMER)
                .setAttribute("knode.event.delivery_attempt", attempt).startSpan();
    }

    public static final class Saved {
        private final String traceparent;
        private final String tracestate;
        private Saved(String traceparent, String tracestate) {
            this.traceparent = traceparent; this.tracestate = tracestate;
        }
        public String traceparent() { return traceparent; }
        public String tracestate() { return tracestate; }
    }
}
