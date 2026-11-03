package com.knode.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded Jaeger query for a persisted producer span, used only by the opt-in test. */
final class ExternalTraceProbe {
    private static final int MAX_RESPONSE_BYTES=1024*1024;
    private final HttpClient http;
    private final String origin;
    private final ObjectMapper json=new ObjectMapper();

    ExternalTraceProbe(String endpoint) {
        origin=ExternalEventGuard.checkJaegerEndpoint(endpoint);
        http=HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(3)).build();
    }

    void awaitProducer(String traceparent,boolean requireGateway,Duration timeout) throws Exception {
        var context=new com.knode.observability.EventTraceContext(io.opentelemetry.api.OpenTelemetry.noop());
        String normalized=context.fromStored(traceparent,null).orElseThrow(() -> new IllegalArgumentException("Invalid producer traceparent")).traceparent();
        String traceId=normalized.substring(3,35),spanId=normalized.substring(36,52);
        long deadline=System.nanoTime()+timeout.toNanos();
        while (System.nanoTime()<deadline) {
            long remaining=deadline-System.nanoTime();
            long requestNanos=Math.min(remaining,TimeUnit.SECONDS.toNanos(3));
            if (requestNanos<=0) break;
            var request=HttpRequest.newBuilder(URI.create(origin+"/api/traces/"+traceId))
                    .timeout(Duration.ofNanos(requestNanos)).GET().build();
            var future=http.sendAsync(request,info -> new LimitedBody());
            try {
                var response=future.get(requestNanos,TimeUnit.NANOSECONDS);
                if (response.statusCode()==200) {
                    JsonNode body;
                    try {body=json.readTree(response.body());}
                    catch (java.io.IOException invalid) {throw new IllegalStateException("Invalid trace query response");}
                    if (containsExpectedSpans(body,traceId,spanId,requireGateway)) return;
                } else if (response.statusCode()!=404) {
                    throw new IllegalStateException("Trace query returned an unexpected status");
                }
            } catch (TimeoutException timeoutFailure) {
                future.cancel(true);
            } catch (InterruptedException interrupted) {
                future.cancel(true);Thread.currentThread().interrupt();throw interrupted;
            } catch (ExecutionException unavailable) {
                for (Throwable cause=unavailable;cause!=null;cause=cause.getCause())
                    if (cause instanceof ResponseTooLarge) throw new IllegalStateException("Trace query response exceeded limit");
            }
            remaining=deadline-System.nanoTime();
            if (remaining>0) TimeUnit.NANOSECONDS.sleep(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(200)));
        }
        throw new IllegalStateException("Required trace spans were not queryable before the deadline");
    }

    private boolean containsExpectedSpans(JsonNode body,String traceId,String spanId,boolean requireGateway) {
        if (body==null || !body.path("data").isArray()) return false;
        for (JsonNode trace:body.path("data")) {
            if (!traceId.equalsIgnoreCase(trace.path("traceID").asText())) continue;
            boolean producer=false,gateway=false;
            JsonNode processes=trace.path("processes");
            for (JsonNode span:trace.path("spans")) {
                if (!traceId.equalsIgnoreCase(span.path("traceID").asText())) continue;
                String service=processes.path(span.path("processID").asText()).path("serviceName").asText();
                if ("knode-user".equals(service) && spanId.equalsIgnoreCase(span.path("spanID").asText())) producer=true;
                if ("knode-gateway".equals(service)) gateway=true;
            }
            if (producer && (!requireGateway || gateway)) return true;
        }
        return false;
    }

    private static final class ResponseTooLarge extends RuntimeException {}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() {return body;}
        @Override public void onSubscribe(Flow.Subscription value) {subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer:buffers) {
                int size=buffer.remaining();
                if (size>MAX_RESPONSE_BYTES-out.size()) {
                    subscription.cancel();body.completeExceptionally(new ResponseTooLarge());return;
                }
                byte[] bytes=new byte[size];buffer.get(bytes);out.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) {body.completeExceptionally(failure);}
        @Override public void onComplete() {body.complete(out.toByteArray());}
    }
}
