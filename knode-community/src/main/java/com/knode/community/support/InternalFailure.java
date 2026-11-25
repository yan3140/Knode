package com.knode.community.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InternalFailure extends RuntimeException {
    public static final String CACHE_POLICY = "no-store";

    private final int status;
    private final String code;
    private final String publicMessage;
    private final boolean retryable;

    public InternalFailure(int status, String code, String publicMessage, boolean retryable) {
        super(code);
        this.status = status;
        this.code = code;
        this.publicMessage = publicMessage;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String publicMessage() {
        return publicMessage;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> body(HttpServletRequest request) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", publicMessage);
        error.put("retryable", retryable);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", error);
        Object reqId = request == null ? null : request.getAttribute("knode.requestId");
        if (reqId instanceof String id && !id.isBlank()) {
            out.put("requestId", id);
        }
        return out;
    }

    public void write(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", CACHE_POLICY);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(OriginPolicy.JSON.writeValueAsString(body(request)));
    }
}
