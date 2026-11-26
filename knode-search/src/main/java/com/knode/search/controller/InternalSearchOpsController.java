package com.knode.search.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.search.auth.SearchSecurityFilter;
import com.knode.search.ops.InternalOpsException;
import com.knode.search.ops.SearchConsumerOpsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/internal/v1/ops/consumers")
public class InternalSearchOpsController {
    private static final Set<String> BATCH_RETRY_FIELDS = Set.of("replayRequestId", "operator", "limit");
    private static final Set<String> SINGLE_RETRY_FIELDS = Set.of("replayRequestId", "operator");

    private final SearchConsumerOpsService opsService;
    private final ObjectMapper objectMapper;

    public InternalSearchOpsController(SearchConsumerOpsService opsService, ObjectMapper objectMapper) {
        this.opsService = opsService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> summary(HttpServletRequest request) {
        requireServicePrincipal(request, SearchSecurityFilter.OPS_READ_PERMISSION);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(opsService.getSummary());
    }

    @GetMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listEvents(
            HttpServletRequest request,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "status", required = false) String status) {
        requireServicePrincipal(request, SearchSecurityFilter.OPS_READ_PERMISSION);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(opsService.listEvents(pageNum, pageSize, status));
    }

    @PostMapping(value = "/retry", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> retryBatch(
            HttpServletRequest request,
            @RequestBody(required = false) String rawBody) {
        ServiceTokenVerifier.Principal principal = requireServicePrincipal(request, SearchSecurityFilter.OPS_RETRY_PERMISSION);
        JsonNode root = parseStrictObject(rawBody, BATCH_RETRY_FIELDS);
        String replayRequestId = readRequiredString(root, "replayRequestId");
        String operator = readOptionalString(root, "operator");
        Integer limit = readOptionalInt(root, "limit");
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(opsService.retryBatch(principal, replayRequestId, operator, limit));
    }

    @PostMapping(value = "/{eventId}/retry", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> retrySingle(
            HttpServletRequest request,
            @PathVariable("eventId") String eventId,
            @RequestBody(required = false) String rawBody) {
        ServiceTokenVerifier.Principal principal = requireServicePrincipal(request, SearchSecurityFilter.OPS_RETRY_PERMISSION);
        JsonNode root = parseStrictObject(rawBody, SINGLE_RETRY_FIELDS);
        String replayRequestId = readRequiredString(root, "replayRequestId");
        String operator = readOptionalString(root, "operator");
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(opsService.retrySingleEvent(principal, eventId, replayRequestId, operator));
    }

    private ServiceTokenVerifier.Principal requireServicePrincipal(HttpServletRequest request, String requiredPermission) {
        Object attr = request.getAttribute(SearchSecurityFilter.ATTR_SERVICE_PRINCIPAL);
        if (!(attr instanceof ServiceTokenVerifier.Principal principal)) {
            throw new InternalOpsException(401, "INVALID_SERVICE_TOKEN", "Missing verified service principal", false);
        }
        if (principal.permissions() == null || !principal.permissions().contains(requiredPermission)) {
            throw new InternalOpsException(403, "SERVICE_PERMISSION_DENIED", "Service token lacks required permission", false);
        }
        return principal;
    }

    private JsonNode parseStrictObject(String rawBody, Set<String> allowedFields) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", "JSON request body is required", false);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", "Malformed JSON request body", false, ex);
        }
        if (root == null || !root.isObject()) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", "Request body must be a JSON object", false);
        }
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!allowedFields.contains(field)) {
                throw new InternalOpsException(400, "INVALID_ARGUMENT", "Unsupported request field: " + field, false);
            }
        }
        return root;
    }

    private static String readRequiredString(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", field + " must be a non-empty string", false);
        }
        return node.asText();
    }

    private static String readOptionalString(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", field + " must be a string", false);
        }
        return node.asText();
    }

    private static Integer readOptionalInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", field + " must be an integer", false);
        }
        return node.intValue();
    }
}
