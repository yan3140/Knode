package com.knode.community.support;

import com.knode.governance.GovernedCall;
import com.knode.observability.RequestCorrelation;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import java.net.http.HttpRequest;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

public final class OutboundContext {
    private OutboundContext() {}

    public static void injectHeaders(HttpRequest.Builder builder, OpenTelemetry telemetry) {
        String requestId = MDC.get("requestId");
        if (RequestCorrelation.valid(requestId)) {
            builder.header("X-Request-Id", requestId);
        }
        if (telemetry != null) {
            telemetry.getPropagators().getTextMapPropagator().inject(
                    Context.current(),
                    builder,
                    (carrier, key, value) -> {
                        if (carrier != null && "traceparent".equalsIgnoreCase(key) && value != null && !value.isBlank()) {
                            carrier.header("traceparent", value);
                        }
                    });
        }
    }

    public static <T> T govern(GovernedCall governed, String resource, Callable<T> call) throws Exception {
        if (governed == null) {
            return call.call();
        }
        return governed.execute(resource, call);
    }
}
