package com.knode.content.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.knode.content.profile.UserProfileClient;
import com.knode.content.runtime.OutboundContext;
import com.knode.governance.GovernedCall;
import io.opentelemetry.api.OpenTelemetry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class CommunityRelationClient {
    public record RelationSnapshot(
            String relationStatus,
            String followingCount,
            String followerCount,
            Boolean viewerFollowing,
            String viewerFollowStatus) {}

    private static final int MAX_BODY_BYTES = 1048576;
    private final URI origin;
    private final UserProfileClient users;
    private final GovernedCall governed;
    private final OpenTelemetry telemetry;
    private final HttpClient http = OriginPolicy.newClient();

    public CommunityRelationClient(
            @Value("${KNODE_COMMUNITY_ORIGIN:}") String origin,
            UserProfileClient users,
            ObjectProvider<GovernedCall> governedProvider,
            ObjectProvider<OpenTelemetry> telemetryProvider) {
        this.origin = OriginPolicy.parseOptional(origin, "community client");
        this.users = users;
        this.governed = governedProvider == null ? null : governedProvider.getIfAvailable();
        this.telemetry = telemetryProvider == null ? null : telemetryProvider.getIfAvailable();
    }

    public boolean isConfigured() {
        return origin != null && users.isConfigured();
    }

    public RelationSnapshot fetch(String targetUserId, String viewerUserId, String viewerToken) {
        boolean viewerApplicable = viewerUserId != null && !viewerUserId.equals(targetUserId);
        if (!OriginPolicy.validPositiveId(targetUserId) || origin == null || !users.isConfigured()) {
            return unavailable(viewerApplicable);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
        try {
            String serviceToken = users.issueServiceToken("knode-community", List.of("community:relations:read"), deadline);
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) throw new TimeoutException();
            long timeout = Math.min(1000, remaining);
            String payload = OriginPolicy.JSON.writeValueAsString(Map.of("userIds", List.of(targetUserId)));
            HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve("/internal/v1/community/relations:batch"))
                    .timeout(Duration.ofMillis(timeout))
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json");
            if (viewerApplicable && viewerToken != null && !viewerToken.isBlank()) {
                builder.header("token", viewerToken);
            }
            OutboundContext.injectHeaders(builder, telemetry);
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            HttpResponse<byte[]> response = OutboundContext.govern(governed, "feign:knode-community#relationsBatch", () -> {
                CompletableFuture<HttpResponse<byte[]>> p = http.sendAsync(request, ignored -> new OriginPolicy.BoundedBody(MAX_BODY_BYTES));
                try {
                    return p.get(timeout, TimeUnit.MILLISECONDS);
                } finally {
                    if (!p.isDone()) p.cancel(true);
                }
            });
            if (response.statusCode() != 200) return unavailable(viewerApplicable);
            JsonNode root = OriginPolicy.JSON.readTree(response.body());
            if (!OriginPolicy.exactFields(root, Set.of("items")) || !root.get("items").isArray() || root.get("items").size() != 1) {
                return unavailable(viewerApplicable);
            }
            JsonNode item = root.get("items").get(0);
            if (!OriginPolicy.exactFields(item, Set.of("userId", "followingCount", "followerCount", "viewerFollowing"))
                    || !item.get("userId").isTextual() || !targetUserId.equals(item.get("userId").asText())
                    || !item.get("followingCount").isTextual() || !OriginPolicy.validNonNegativeCount(item.get("followingCount").asText())
                    || !item.get("followerCount").isTextual() || !OriginPolicy.validNonNegativeCount(item.get("followerCount").asText())) {
                return unavailable(viewerApplicable);
            }
            JsonNode viewerNode = item.get("viewerFollowing");
            if (!viewerNode.isNull() && !viewerNode.isBoolean()) {
                return unavailable(viewerApplicable);
            }
            Boolean viewerFollowing = viewerApplicable && viewerNode.isBoolean() ? viewerNode.asBoolean() : null;
            String viewerStatus = !viewerApplicable ? "NOT_APPLICABLE" : (viewerFollowing != null ? "AVAILABLE" : "UNAVAILABLE");
            return new RelationSnapshot(
                    "AVAILABLE",
                    item.get("followingCount").asText(),
                    item.get("followerCount").asText(),
                    viewerFollowing,
                    viewerStatus);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return unavailable(viewerApplicable);
        }
    }

    private static RelationSnapshot unavailable(boolean viewerApplicable) {
        return new RelationSnapshot("UNAVAILABLE", null, null, null, viewerApplicable ? "UNAVAILABLE" : "NOT_APPLICABLE");
    }
}
