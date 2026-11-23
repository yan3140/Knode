package com.knode.content.relay;

import com.knode.observability.EventTraceContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public final class FactTrace {
    private final EventTraceContext trace;
    public FactTrace(ObjectProvider<EventTraceContext> configured) {
        trace=configured.getIfAvailable(() -> new EventTraceContext(OpenTelemetry.propagating(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()))));
    }
    public boolean validStored(String parent) {
        try { return parent!=null && trace.fromStored(parent,null).isPresent(); }
        catch(RuntimeException failure) { return false; }
    }
    public void capture(Map<String,Object> event) {
        try { trace.captureCurrent().ifPresent(saved -> event.put("traceContext",Map.of("traceparent",saved.traceparent()))); }
        catch(RuntimeException ignored) { /* telemetry must not block fact creation */ }
    }
}
