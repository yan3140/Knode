package com.knode.content.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class SnapshotFailure extends RuntimeException {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String CACHE_POLICY = "no-store";
    public static final int RETRY_AFTER_SECONDS = 5;

    public enum Code {
        INVALID_ARGUMENT(400, "Invalid request", false),
        INVALID_SERVICE_TOKEN(401, "Invalid service credentials", false),
        SERVICE_PERMISSION_DENIED(403, "Service permission denied", false),
        IDEMPOTENCY_CONFLICT(409, "Request ID already has a different purpose", false),
        SNAPSHOT_EXPIRED(410, "Snapshot is unavailable or expired", false),
        CURSOR_EXPIRED(410, "Incremental cursor is no longer usable", false),
        RESPONSE_TOO_LARGE(413, "Response exceeds budget", false),
        SNAPSHOT_TOO_LARGE(413, "Snapshot exceeds export capacity", false),
        RATE_LIMITED(429, "Snapshot export capacity is busy", true),
        FEATURE_DISABLED(503, "Snapshot API is disabled", true),
        AUTH_STATE_UNAVAILABLE(503, "Service authentication is unavailable", true),
        DEPENDENCY_UNAVAILABLE(503, "Content data is unavailable", true);

        final int status;
        final String message;
        final boolean retryable;

        Code(int status, String message, boolean retryable) {
            this.status = status;
            this.message = message;
            this.retryable = retryable;
        }
    }

    private final Code code;

    public SnapshotFailure(Code code) {
        super(code.name());
        this.code = code;
    }

    public int status() {
        return code.status;
    }

    record Detail(String code, String message, boolean retryable) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorBody(Detail error, String requestId, String traceId) {}

    ErrorBody body(HttpServletRequest request) {
        var span = Span.current().getSpanContext();
        return new ErrorBody(new Detail(code.name(), code.message, code.retryable),
                (String) request.getAttribute("knode.requestId"), span.isValid() ? span.getTraceId() : null);
    }

    public static void writeAuth(HttpServletRequest request, HttpServletResponse response, int status) throws IOException {
        Code code = switch (status) {
            case 403 -> Code.SERVICE_PERMISSION_DENIED;
            case 503 -> Code.AUTH_STATE_UNAVAILABLE;
            default -> Code.INVALID_SERVICE_TOKEN;
        };
        response.setStatus(code.status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", CACHE_POLICY);
        JSON.writeValue(response.getOutputStream(), new SnapshotFailure(code).body(request));
    }
}
