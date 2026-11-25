package com.knode.community.ops;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.community.auth.AuthenticationFilter;
import com.knode.community.auth.MachineAuth;
import com.knode.community.support.InternalFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommunityOpsController {
    private static final int MAX_BODY_BYTES = 4_096;
    private static final Set<String> ALLOWED_EVENT_QUERY_KEYS = Set.of("pageNum", "pageSize", "status");
    private static final Set<String> ALLOWED_RETRY_FIELDS = Set.of("replayRequestId", "operator");

    private static final ObjectMapper STRICT_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_BODY_BYTES)
                    .maxNestingDepth(3)
                    .maxStringLength(256)
                    .maxNumberLength(32)
                    .build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private record ParsedRetryBody(String replayRequestId, String operator) {}

    private final CommunityOpsService opsService;

    public CommunityOpsController(CommunityOpsService opsService) {
        this.opsService = opsService;
    }

    @GetMapping(AuthenticationFilter.INTERNAL_OPS_SUMMARY)
    public ResponseEntity<Map<String, Object>> summary(HttpServletRequest request) {
        requireMachinePrincipal(request, AuthenticationFilter.PERM_OPS_READ);
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw invalidArgument("Unexpected query parameters");
        }
        return ResponseEntity.ok()
                .header("Cache-Control", InternalFailure.CACHE_POLICY)
                .body(opsService.summary());
    }

    @GetMapping(AuthenticationFilter.INTERNAL_OPS_EVENTS)
    public ResponseEntity<Map<String, Object>> events(HttpServletRequest request) {
        requireMachinePrincipal(request, AuthenticationFilter.PERM_OPS_READ);
        Map<String, String[]> params = request.getParameterMap();
        for (Map.Entry<String, String[]> entry : params.entrySet()) {
            if (!ALLOWED_EVENT_QUERY_KEYS.contains(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().length != 1) {
                throw invalidArgument("Invalid consumer query parameters");
            }
        }
        String pageNum = singleParam(params, "pageNum");
        String pageSize = singleParam(params, "pageSize");
        String status = singleParam(params, "status");
        return ResponseEntity.ok()
                .header("Cache-Control", InternalFailure.CACHE_POLICY)
                .body(opsService.events(pageNum, pageSize, status));
    }

    @PostMapping(AuthenticationFilter.INTERNAL_OPS_RETRY)
    public ResponseEntity<Map<String, Object>> retryAll(HttpServletRequest request) {
        ServiceTokenVerifier.Principal principal = requireMachinePrincipal(request, AuthenticationFilter.PERM_OPS_RETRY);
        ParsedRetryBody body = parseRetryBody(request);
        Map<String, Object> result = opsService.retry(null, body.replayRequestId(), body.operator(), principal.service());
        return ResponseEntity.ok()
                .header("Cache-Control", InternalFailure.CACHE_POLICY)
                .body(result);
    }

    @PostMapping("/internal/v1/ops/consumers/{eventId}/retry")
    public ResponseEntity<Map<String, Object>> retrySingle(
            @PathVariable("eventId") String eventId,
            HttpServletRequest request) {
        ServiceTokenVerifier.Principal principal = requireMachinePrincipal(request, AuthenticationFilter.PERM_OPS_RETRY);
        ParsedRetryBody body = parseRetryBody(request);
        Map<String, Object> result = opsService.retry(eventId, body.replayRequestId(), body.operator(), principal.service());
        return ResponseEntity.ok()
                .header("Cache-Control", InternalFailure.CACHE_POLICY)
                .body(result);
    }

    private static ServiceTokenVerifier.Principal requireMachinePrincipal(HttpServletRequest request, String requiredPermission) {
        Object attr = request.getAttribute(MachineAuth.PRINCIPAL);
        if (!(attr instanceof ServiceTokenVerifier.Principal principal)) {
            throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Missing verified service principal", false);
        }
        if (!MachineAuth.AUDIENCE.equals(principal.audience())
                || principal.permissions() == null
                || !principal.permissions().contains(requiredPermission)) {
            throw new InternalFailure(403, "SERVICE_PERMISSION_DENIED", "Service permission denied", false);
        }
        return principal;
    }

    private static ParsedRetryBody parseRetryBody(HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw invalidArgument("Invalid retry request");
        }
        try {
            if (request.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
                throw invalidArgument("Content-Type must be application/json");
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) {
                throw invalidArgument("Invalid retry request body size");
            }
            JsonNode root = STRICT_JSON.readTree(bytes);
            if (root == null || !root.isObject() || !root.has("replayRequestId")) {
                throw invalidArgument("Missing replayRequestId");
            }
            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String name = fieldNames.next();
                if (!ALLOWED_RETRY_FIELDS.contains(name)) {
                    throw invalidArgument("Unexpected field in retry request");
                }
            }
            JsonNode reqIdNode = root.get("replayRequestId");
            if (reqIdNode == null || !reqIdNode.isTextual()) {
                throw invalidArgument("replayRequestId must be string");
            }
            String operator = null;
            if (root.has("operator") && !root.get("operator").isNull()) {
                JsonNode opNode = root.get("operator");
                if (!opNode.isTextual()) {
                    throw invalidArgument("operator must be string");
                }
                operator = opNode.textValue();
            }
            return new ParsedRetryBody(reqIdNode.textValue(), operator);
        } catch (InternalFailure failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            throw invalidArgument("Malformed retry request body");
        }
    }

    private static String singleParam(Map<String, String[]> params, String key) {
        String[] values = params.get(key);
        return (values == null || values.length == 0) ? null : values[0];
    }

    private static InternalFailure invalidArgument(String message) {
        return new InternalFailure(400, "INVALID_ARGUMENT", message, false);
    }

    @ExceptionHandler(InternalFailure.class)
    public ResponseEntity<Map<String, Object>> internalError(InternalFailure failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status())
                .header("Cache-Control", InternalFailure.CACHE_POLICY)
                .body(failure.body(request));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> dependencyError(HttpServletRequest request) {
        InternalFailure unavailable = new InternalFailure(
                503,
                "DEPENDENCY_UNAVAILABLE",
                "Community consumer ops service unavailable",
                true);
        return ResponseEntity.status(503)
                .header("Cache-Control", InternalFailure.CACHE_POLICY)
                .body(unavailable.body(request));
    }
}
