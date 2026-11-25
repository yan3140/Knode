package com.knode.community.follow;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.auth.UserTokenVerifier;
import com.knode.community.auth.AuthenticationFilter;
import com.knode.community.auth.MachineAuth;
import com.knode.community.notification.NotificationService;
import com.knode.community.notification.NotificationTransaction;
import com.knode.community.support.InternalFailure;
import com.knode.community.support.OriginPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalCommunityController {
    private static final int MAX_REQUEST_BYTES = 8_192;
    private static final int MAX_BATCH_IDS = 100;
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_REQUEST_BYTES)
                    .maxNestingDepth(4)
                    .maxStringLength(512)
                    .maxNumberLength(32)
                    .build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final FollowService followService;
    private final NotificationService notificationService;

    public InternalCommunityController(FollowService followService, NotificationService notificationService) {
        this.followService = followService;
        this.notificationService = notificationService;
    }

    @PostMapping(AuthenticationFilter.INTERNAL_RELATIONS_BATCH)
    public ResponseEntity<Map<String, Object>> relationsBatch(HttpServletRequest request) {
        requireMachinePermission(request, AuthenticationFilter.PERM_RELATIONS_READ);
        List<Long> distinctUserIds = parseUserIds(request);
        Long viewerUserId = optionalViewerId(request);
        List<FollowTransaction.RelationBatchItem> items = followService.relationsBatch(distinctUserIds, viewerUserId);
        return ResponseEntity.ok()
                .header("Cache-Control", InternalFailure.CACHE_POLICY)
                .body(Map.of("items", items));
    }

    @GetMapping(AuthenticationFilter.INTERNAL_NOTIFICATIONS_SUMMARY)
    public ResponseEntity<NotificationTransaction.NotificationSummaryDto> notificationsSummary(HttpServletRequest request) {
        requireMachinePermission(request, AuthenticationFilter.PERM_NOTIFICATIONS_READ);
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw invalidArgument();
        }
        Long receiverId = optionalViewerId(request);
        if (receiverId == null) {
            throw new InternalFailure(401, "SESSION_REVOKED", "Receiver user token is required", false);
        }
        NotificationTransaction.NotificationSummaryDto summary = notificationService.summary(receiverId);
        return ResponseEntity.ok()
                .header("Cache-Control", InternalFailure.CACHE_POLICY)
                .body(summary);
    }

    private static void requireMachinePermission(HttpServletRequest request, String requiredPermission) {
        Object attr = request.getAttribute(MachineAuth.PRINCIPAL);
        if (!(attr instanceof ServiceTokenVerifier.Principal principal)) {
            throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Missing verified service principal", false);
        }
        if (!MachineAuth.AUDIENCE.equals(principal.audience())
                || principal.permissions() == null
                || !principal.permissions().contains(requiredPermission)) {
            throw new InternalFailure(403, "SERVICE_PERMISSION_DENIED", "Service permission denied", false);
        }
    }

    private static Long optionalViewerId(HttpServletRequest request) {
        Object attr = request.getAttribute(AuthenticationFilter.PRINCIPAL);
        if (attr == null) {
            return null;
        }
        if (attr instanceof UserTokenVerifier.Principal viewer && OriginPolicy.validPositiveId(viewer.userId())) {
            return Long.parseLong(viewer.userId());
        }
        throw new InternalFailure(401, "SESSION_REVOKED", "Invalid viewer principal", false);
    }

    private static List<Long> parseUserIds(HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            throw invalidArgument();
        }
        try {
            if (request.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
                throw invalidArgument();
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES) {
                throw invalidArgument();
            }
            JsonNode root = STRICT_JSON.readTree(bytes);
            if (!OriginPolicy.exactFields(root, Set.of("userIds"))) {
                throw invalidArgument();
            }
            JsonNode idsNode = root.get("userIds");
            if (idsNode == null || !idsNode.isArray() || idsNode.isEmpty() || idsNode.size() > MAX_BATCH_IDS) {
                throw invalidArgument();
            }
            LinkedHashSet<Long> distinct = new LinkedHashSet<>();
            for (JsonNode idNode : idsNode) {
                if (idNode == null || !idNode.isTextual() || !OriginPolicy.validPositiveId(idNode.textValue())) {
                    throw invalidArgument();
                }
                distinct.add(Long.parseLong(idNode.textValue()));
            }
            return List.copyOf(distinct);
        } catch (InternalFailure failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            throw invalidArgument();
        }
    }

    private static InternalFailure invalidArgument() {
        return new InternalFailure(400, "INVALID_ARGUMENT", "Invalid relations batch request", false);
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
                "Community relation data is unavailable",
                true);
        return ResponseEntity.status(503)
                .header("Cache-Control", InternalFailure.CACHE_POLICY)
                .body(unavailable.body(request));
    }
}
