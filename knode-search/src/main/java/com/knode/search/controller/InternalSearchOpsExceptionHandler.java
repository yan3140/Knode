package com.knode.search.controller;

import com.knode.search.ops.InternalOpsException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = InternalSearchOpsController.class)
public class InternalSearchOpsExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalSearchOpsExceptionHandler.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @ExceptionHandler(InternalOpsException.class)
    public ResponseEntity<Map<String, Object>> handleInternalOpsException(
            InternalOpsException ex, HttpServletRequest request) {
        return buildErrorResponse(ex.httpStatus(), ex.errorCode(), ex.getMessage(), ex.retryable(), request);
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex, HttpServletRequest request) {
        return buildErrorResponse(400, "INVALID_ARGUMENT", "Invalid request parameter or body", false, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return buildErrorResponse(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json", false, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return buildErrorResponse(405, "METHOD_NOT_ALLOWED", "HTTP method not allowed", false, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        LOGGER.warn("event=search_internal_ops_unexpected_error errorType={}", ex.getClass().getSimpleName());
        return buildErrorResponse(503, "DEPENDENCY_UNAVAILABLE", "Internal search ops temporarily unavailable", true, request);
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            int httpStatus,
            String errorCode,
            String message,
            boolean retryable,
            HttpServletRequest request) {
        Map<String, Object> errorObj = new LinkedHashMap<>();
        errorObj.put("code", errorCode);
        errorObj.put("message", message);
        errorObj.put("retryable", retryable);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorObj);
        String requestId = sanitizeRequestId(request == null ? null : request.getHeader("X-Request-Id"));
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return ResponseEntity.status(httpStatus)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .body(body);
    }

    private static String sanitizeRequestId(String requestId) {
        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            return null;
        }
        return requestId;
    }
}
