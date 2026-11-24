package com.knode.content.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.knode.auth.AuthFailure;
import com.knode.auth.UserTokenVerifier;
import com.knode.content.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/admin/ops/outbox", "/ops/outbox"})
public class OpsFacadeController {
    private static final int MAX_BODY_BYTES = 16384;
    private static final String[] READ_PERMS = {"ops:outbox:read", "content:ops:read"};
    private static final String[] WRITE_PERMS = {"ops:outbox:retry", "ops:outbox:redeliver", "content:ops:redeliver"};

    private final AdminGuard guard;
    private final OpsFacadeService service;

    public OpsFacadeController(AdminGuard guard, OpsFacadeService service) {
        this.guard = guard;
        this.service = service;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(HttpServletRequest request) {
        guard.require(request, READ_PERMS);
        return Map.of("code", 200, "msg", "\u64cd\u4f5c\u6210\u529f", "data", service.summary());
    }

    @GetMapping("/search")
    public Map<String, Object> searchEvents(
            @RequestParam(required = false) String pageNum,
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        guard.require(request, READ_PERMS);
        return Map.of("code", 200, "msg", "\u64cd\u4f5c\u6210\u529f", "data",
                service.peerEvents(PeerOpsClient.Peer.SEARCH, pageNum, pageSize, status));
    }

    @GetMapping("/notification")
    public Map<String, Object> notificationEvents(
            @RequestParam(required = false) String pageNum,
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        guard.require(request, READ_PERMS);
        return Map.of("code", 200, "msg", "\u64cd\u4f5c\u6210\u529f", "data",
                service.peerEvents(PeerOpsClient.Peer.NOTIFICATION, pageNum, pageSize, status));
    }

    @PostMapping("/search/retry")
    public Map<String, Object> retrySearch(@RequestBody(required = false) byte[] raw, HttpServletRequest request) {
        UserTokenVerifier.Principal principal = guard.require(request, WRITE_PERMS);
        PeerRetryBody body = parsePeerRetry(raw);
        return Map.of("code", 200, "msg", "\u64cd\u4f5c\u6210\u529f", "data",
                service.retryPeer(PeerOpsClient.Peer.SEARCH, body.replayRequestId(), body.eventId(), principal.userId()));
    }

    @PostMapping("/notification/retry")
    public Map<String, Object> retryNotification(@RequestBody(required = false) byte[] raw, HttpServletRequest request) {
        UserTokenVerifier.Principal principal = guard.require(request, WRITE_PERMS);
        PeerRetryBody body = parsePeerRetry(raw);
        return Map.of("code", 200, "msg", "\u64cd\u4f5c\u6210\u529f", "data",
                service.retryPeer(PeerOpsClient.Peer.NOTIFICATION, body.replayRequestId(), body.eventId(), principal.userId()));
    }

    @PostMapping("/content/{eventId}/redeliver")
    public Map<String, Object> redeliverContent(
            @PathVariable String eventId,
            @RequestBody(required = false) byte[] raw,
            HttpServletRequest request) {
        UserTokenVerifier.Principal principal = guard.require(request, WRITE_PERMS);
        JsonNode root = parseRequiredObject(raw, Set.of("replayRequestId"));
        if (!root.has("replayRequestId") || !root.get("replayRequestId").isTextual()) {
            throw new FacadeFailure(400, 400, "Invalid replayRequestId");
        }
        return Map.of("code", 200, "msg", "\u64cd\u4f5c\u6210\u529f", "data",
                service.redeliverContentEvent(eventId, root.get("replayRequestId").asText(), principal.userId()));
    }

    @PostMapping("/retry")
    public ResponseEntity<Map<String, Object>> retryAll(@RequestBody(required = false) byte[] raw, HttpServletRequest request) {
        UserTokenVerifier.Principal principal = guard.require(request, WRITE_PERMS);
        MultiRetryBody body = parseMultiRetry(raw);
        OpsFacadeService.MultiDomainRetryResult result = service.retryAll(body.replayRequestId(), body.contentEventIds(), principal.userId());
        String msg = result.httpStatus() == 200 ? "\u64cd\u4f5c\u6210\u529f" : "\u6240\u6709\u914d\u7f6e\u57df\u91cd\u8bd5\u5931\u8d25";
        return ResponseEntity.status(result.httpStatus())
                .body(Map.of("code", result.businessCode(), "msg", msg, "data", result.data()));
    }

    private record PeerRetryBody(String replayRequestId, String eventId) {}
    private record MultiRetryBody(String replayRequestId, List<String> contentEventIds) {}

    private static PeerRetryBody parsePeerRetry(byte[] raw) {
        JsonNode root = parseOptionalObject(raw, Set.of("replayRequestId", "eventId"));
        if (root == null) return new PeerRetryBody(null, null);
        String replayRequestId = optionalText(root, "replayRequestId");
        String eventId = optionalText(root, "eventId");
        return new PeerRetryBody(replayRequestId, eventId);
    }

    private static MultiRetryBody parseMultiRetry(byte[] raw) {
        JsonNode root = parseOptionalObject(raw, Set.of("replayRequestId", "contentEventIds"));
        if (root == null) return new MultiRetryBody(null, List.of());
        String replayRequestId = optionalText(root, "replayRequestId");
        List<String> eventIds = List.of();
        if (root.has("contentEventIds") && !root.get("contentEventIds").isNull()) {
            JsonNode arr = root.get("contentEventIds");
            if (!arr.isArray() || arr.size() > 20) {
                throw new FacadeFailure(400, 400, "Invalid contentEventIds");
            }
            LinkedHashSet<String> distinct = new LinkedHashSet<>();
            for (JsonNode item : arr) {
                if (!item.isTextual()) throw new FacadeFailure(400, 400, "Invalid contentEventIds");
                String id = item.asText();
                try {
                    if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
                } catch (IllegalArgumentException bad) {
                    throw new FacadeFailure(400, 400, "Invalid contentEventIds");
                }
                distinct.add(id);
            }
            eventIds = new ArrayList<>(distinct);
        }
        return new MultiRetryBody(replayRequestId, eventIds);
    }

    private static JsonNode parseRequiredObject(byte[] raw, Set<String> allowedFields) {
        if (raw == null || raw.length == 0) throw new FacadeFailure(400, 400, "Request body required");
        return parseOptionalObject(raw, allowedFields);
    }

    private static JsonNode parseOptionalObject(byte[] raw, Set<String> allowedFields) {
        if (raw == null || raw.length == 0) return null;
        if (raw.length > MAX_BODY_BYTES) throw new FacadeFailure(400, 400, "Request body too large");
        try {
            JsonNode root = OriginPolicy.JSON.readTree(raw);
            if (root == null || !root.isObject()) throw new FacadeFailure(400, 400, "Invalid request body");
            var fields = root.fieldNames();
            while (fields.hasNext()) {
                if (!allowedFields.contains(fields.next())) {
                    throw new FacadeFailure(400, 400, "Unexpected request field");
                }
            }
            return root;
        } catch (FacadeFailure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new FacadeFailure(400, 400, "Malformed JSON");
        }
    }

    private static String optionalText(JsonNode root, String field) {
        if (!root.has(field) || root.get(field).isNull()) return null;
        if (!root.get(field).isTextual()) throw new FacadeFailure(400, 400, "Invalid " + field);
        return root.get(field).asText();
    }

    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String, Object>> authError(AuthFailure failure) {
        int code = failure.status() == 503 ? 500 : failure.status();
        return ResponseEntity.status(failure.status()).body(Map.of("code", code, "msg", "Authentication failed"));
    }

    @ExceptionHandler(FacadeFailure.class)
    public ResponseEntity<Map<String, Object>> facadeError(FacadeFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.code(), "msg", failure.publicMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> systemError() {
        return ResponseEntity.status(503).body(Map.of("code", 500, "msg", "Service unavailable"));
    }
}
