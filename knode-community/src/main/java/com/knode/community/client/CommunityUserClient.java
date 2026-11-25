package com.knode.community.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.knode.community.support.OriginPolicy;
import com.knode.community.support.OutboundContext;
import com.knode.governance.GovernedCall;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class CommunityUserClient implements AutoCloseable {
    public record TargetValidity(boolean available, boolean exists, boolean active) {
        public static TargetValidity unavailable() {
            return new TargetValidity(false, false, false);
        }
    }

    public record AccountValidity(boolean exists, boolean active) {
        public boolean eligible() {
            return exists && active;
        }
    }

    public record ValidityBatch(boolean available, Map<String, AccountValidity> accounts) {
        private static ValidityBatch unavailable() {
            return new ValidityBatch(false, Map.of());
        }
    }

    public record PublicProfile(String status, String nickName, String avatar) {}

    public record ProfileBatch(boolean available, Map<String, PublicProfile> profiles) {
        public static ProfileBatch unavailable() {
            return new ProfileBatch(false, Map.of());
        }
    }

    private record TokenKey(String audience, List<String> permissions) {}
    private record CachedToken(String accessToken, long refreshAfterMillis, long expiresAtMillis) {}

    private static final int USER_BATCH_SIZE = 100;
    private static final int VALIDITY_PARALLELISM = 8;
    private static final int VALIDITY_WORKERS = 32;
    private static final int MAX_BODY_BYTES = 3_145_728;
    private static final long TOTAL_BUDGET_MS = 2_000L;

    private final URI origin;
    private final String basic;
    private final Clock clock;
    private final GovernedCall governed;
    private final OpenTelemetry telemetry;
    private final HttpClient http = OriginPolicy.newClient();
    // Direct handoff bounds dependency load even when multiple inbound requests fan out.
    private final ThreadPoolExecutor validityExecutor = new ThreadPoolExecutor(
            VALIDITY_WORKERS, VALIDITY_WORKERS, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
            work -> {
                Thread thread = new Thread(work, "community-user-validity");
                thread.setDaemon(true);
                return thread;
            });
    private final ConcurrentHashMap<TokenKey, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public CommunityUserClient(String origin, String serviceId, String secret, Clock clock) {
        this(origin, serviceId, secret, clock, null, null);
    }

    @Autowired
    public CommunityUserClient(
            @Value("${KNODE_USER_ORIGIN:}") String origin,
            @Value("${KNODE_USER_SERVICE_ID:}") String serviceId,
            @Value("${KNODE_USER_BOOTSTRAP_SECRET:}") String secret,
            Clock clock,
            ObjectProvider<GovernedCall> governedProvider,
            ObjectProvider<OpenTelemetry> telemetryProvider) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.governed = governedProvider == null ? null : governedProvider.getIfAvailable();
        this.telemetry = telemetryProvider == null ? null : telemetryProvider.getIfAvailable();
        if (origin.isBlank() && serviceId.isBlank() && secret.isBlank()) {
            this.origin = null;
            this.basic = null;
            return;
        }
        int secretBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (origin.isBlank()
                || !serviceId.matches("[a-z][a-z0-9-]{0,63}")
                || secret.isBlank()
                || secretBytes > 72) {
            throw new IllegalArgumentException("Invalid community user client configuration");
        }
        this.origin = OriginPolicy.parseOptional(origin, "user client");
        this.basic = "Basic " + Base64.getEncoder().encodeToString((serviceId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isConfigured() {
        return origin != null && basic != null;
    }

    public void clearTokenCache() {
        tokenCache.clear();
    }

    public String issueServiceToken(String audience, List<String> permissions, long deadlineNanos) throws Exception {
        if (origin == null || basic == null) {
            throw new IllegalStateException("User client unconfigured");
        }
        if (audience == null
                || !audience.matches("[a-z][a-z0-9-]{0,63}")
                || permissions == null
                || permissions.isEmpty()
                || permissions.size() > 10) {
            throw new IllegalArgumentException("Invalid service token request");
        }
        List<String> canonicalPerms = permissions.stream().distinct().sorted().toList();
        if (canonicalPerms.size() != permissions.size()) {
            throw new IllegalArgumentException("Duplicate requested permissions");
        }
        TokenKey key = new TokenKey(audience, canonicalPerms);
        long nowMillis = clock.millis();
        CachedToken cached = tokenCache.get(key);
        if (cached != null && nowMillis < cached.refreshAfterMillis() && nowMillis + 1_500L < cached.expiresAtMillis()) {
            return cached.accessToken();
        }

        String payload = OriginPolicy.JSON.writeValueAsString(Map.of("audience", audience, "permissions", canonicalPerms));
        JsonNode token = post("/internal/v1/auth/service-tokens", basic, payload, deadlineNanos, "feign:knode-user#serviceTokens");
        if (!OriginPolicy.exactFields(token, Set.of("accessToken", "tokenType", "expiresIn", "audience", "permissions"))
                || !text(token, "accessToken")
                || token.get("accessToken").asText().isBlank()
                || token.get("accessToken").asText().length() > 8192
                || token.get("accessToken").asText().codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || !"Bearer".equals(token.path("tokenType").asText())
                || !audience.equals(token.path("audience").asText())
                || !token.path("expiresIn").isIntegralNumber()
                || !token.path("expiresIn").canConvertToInt()
                || token.path("expiresIn").asInt() < 1
                || token.path("expiresIn").asInt() > 300
                || !token.path("permissions").isArray()
                || token.path("permissions").size() != canonicalPerms.size()) {
            throw new IllegalArgumentException("Invalid service token response");
        }
        Set<String> returned = new HashSet<>();
        for (JsonNode permNode : token.path("permissions")) {
            if (!permNode.isTextual() || !returned.add(permNode.asText())) {
                throw new IllegalArgumentException("Invalid service token permissions");
            }
        }
        if (!returned.equals(Set.copyOf(canonicalPerms))) {
            throw new IllegalArgumentException("Mismatched service token permissions");
        }
        String accessToken = token.get("accessToken").asText();
        long ttlMs = token.path("expiresIn").asInt() * 1000L;
        long issuedAtMs = clock.millis();
        long expiresAtMs = issuedAtMs + ttlMs;
        long safetyWindowMs = Math.min(15_000L, Math.max(2_000L, ttlMs / 4));
        long refreshAfterMs = expiresAtMs - safetyWindowMs;
        if (refreshAfterMs > issuedAtMs) {
            tokenCache.put(key, new CachedToken(accessToken, refreshAfterMs, expiresAtMs));
        }
        return accessToken;
    }

    public TargetValidity checkFollowTarget(String userId) {
        if (!OriginPolicy.validPositiveId(userId)) {
            return TargetValidity.unavailable();
        }
        ValidityBatch result = lookupValidity(List.of(userId), deadlineAfter(TOTAL_BUDGET_MS));
        if (!result.available()) {
            return TargetValidity.unavailable();
        }
        AccountValidity account = result.accounts().get(userId);
        return new TargetValidity(true, account.exists(), account.active());
    }

    public ValidityBatch lookupValidity(Collection<String> rawIds, long deadlineNanos) {
        if (rawIds.isEmpty()) {
            return new ValidityBatch(true, Map.of());
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(rawIds));
        if (!isConfigured() || ids.stream().anyMatch(id -> !OriginPolicy.validPositiveId(id))) {
            return ValidityBatch.unavailable();
        }
        Set<Future<Map<String, AccountValidity>>> inFlight = new HashSet<>();
        var completed = new ExecutorCompletionService<Map<String, AccountValidity>>(validityExecutor);
        Context traceContext = Context.current();
        String requestId = MDC.get("requestId");
        try {
            String bearer = "Bearer " + issueServiceToken("knode-user", List.of("users:state:read"), deadlineNanos);
            Map<String, AccountValidity> accounts = new LinkedHashMap<>();
            int next = 0;
            while (next < ids.size() || !inFlight.isEmpty()) {
                requireRemainingNanos(deadlineNanos);
                while (next < ids.size() && inFlight.size() < VALIDITY_PARALLELISM) {
                    int end = Math.min(next + USER_BATCH_SIZE, ids.size());
                    List<String> batch = ids.subList(next, end);
                    inFlight.add(completed.submit(() -> {
                        try (var scope = traceContext.makeCurrent()) {
                            if (requestId != null) {
                                MDC.put("requestId", requestId);
                            }
                            return fetchValidity(batch, bearer, deadlineNanos);
                        } finally {
                            MDC.remove("requestId");
                        }
                    }));
                    next = end;
                }
                var finished = completed.poll(requireRemainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
                if (finished == null) {
                    throw new TimeoutException("User validity budget exhausted");
                }
                inFlight.remove(finished);
                accounts.putAll(finished.get());
            }
            requireRemainingNanos(deadlineNanos);
            return new ValidityBatch(true, Map.copyOf(accounts));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ValidityBatch.unavailable();
        } finally {
            inFlight.forEach(pending -> pending.cancel(true));
        }
    }

    private Map<String, AccountValidity> fetchValidity(List<String> batch, String bearer, long deadlineNanos) throws Exception {
        String body = OriginPolicy.JSON.writeValueAsString(Map.of("userIds", batch, "purpose", "FOLLOW_TARGET"));
        JsonNode result = post("/internal/v1/users/validity:batch", bearer, body, deadlineNanos,
                "feign:knode-user#validityBatch");
        if (!OriginPolicy.exactFields(result, Set.of("items"))
                || !result.get("items").isArray() || result.get("items").size() != batch.size()) {
            throw new IllegalArgumentException("Incomplete User validity response");
        }
        Map<String, AccountValidity> accounts = new LinkedHashMap<>();
        for (int i = 0; i < batch.size(); i++) {
            JsonNode item = result.get("items").get(i);
            String id = batch.get(i);
            if (!OriginPolicy.exactFields(item, Set.of("userId", "exists", "active"))
                    || !text(item, "userId") || !id.equals(item.get("userId").asText())
                    || !item.get("exists").isBoolean() || !item.get("active").isBoolean()) {
                throw new IllegalArgumentException("Invalid User validity response");
            }
            boolean exists = item.get("exists").asBoolean();
            boolean active = item.get("active").asBoolean();
            if (active && !exists) {
                throw new IllegalArgumentException("Inconsistent User validity response");
            }
            accounts.put(id, new AccountValidity(exists, active));
        }
        return accounts;
    }

    private static long requireRemainingNanos(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("User validity budget exhausted");
        }
        return remaining;
    }

    @PreDestroy
    @Override
    public void close() {
        validityExecutor.shutdownNow();
    }

    public ProfileBatch lookupProfiles(Collection<String> rawIds) {
        return lookupProfiles(rawIds, deadlineAfter(TOTAL_BUDGET_MS));
    }

    public ProfileBatch lookupProfiles(Collection<String> rawIds, long requestDeadlineNanos) {
        if (rawIds == null || rawIds.isEmpty()) {
            return new ProfileBatch(true, Map.of());
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>(rawIds);
        if (distinct.size() > 100 || distinct.stream().anyMatch(id -> !OriginPolicy.validPositiveId(id)) || !isConfigured()) {
            return ProfileBatch.unavailable();
        }
        long deadline = Math.min(requestDeadlineNanos, deadlineAfter(TOTAL_BUDGET_MS));
        try {
            String accessToken = issueServiceToken("knode-user", List.of("users:public:read"), deadline);
            List<String> orderedIds = new ArrayList<>(distinct);
            String body = OriginPolicy.JSON.writeValueAsString(Map.of("userIds", orderedIds));
            JsonNode result = post(
                    "/internal/v1/users/public-profiles:batch",
                    "Bearer " + accessToken,
                    body,
                    deadline,
                    "feign:knode-user#publicProfiles");
            if (!OriginPolicy.exactFields(result, Set.of("items"))
                    || !result.get("items").isArray()
                    || result.get("items").size() != orderedIds.size()) {
                return ProfileBatch.unavailable();
            }
            Map<String, PublicProfile> parsed = new LinkedHashMap<>();
            for (int index = 0; index < orderedIds.size(); index++) {
                JsonNode item = result.get("items").get(index);
                String expectedId = orderedIds.get(index);
                if (item == null || !item.isObject() || !text(item, "userId") || !text(item, "status")
                        || !expectedId.equals(item.get("userId").asText()) || parsed.containsKey(expectedId)) {
                    return ProfileBatch.unavailable();
                }
                String status = item.get("status").asText();
                if ("MISSING".equals(status)) {
                    if (!OriginPolicy.exactFields(item, Set.of("userId", "status"))) {
                        return ProfileBatch.unavailable();
                    }
                    parsed.put(expectedId, new PublicProfile("MISSING", null, null));
                } else if ("FOUND".equals(status)) {
                    if (!OriginPolicy.exactFields(item, Set.of("userId", "status", "nickName", "avatar", "profileRevision", "updatedAt"))
                            || !nullableText(item.get("nickName"))
                            || !nullableText(item.get("avatar"))
                            || !text(item, "profileRevision")
                            || !OriginPolicy.validPositiveId(item.get("profileRevision").asText())
                            || !text(item, "updatedAt")
                            || !item.get("updatedAt").asText().endsWith("Z")) {
                        return ProfileBatch.unavailable();
                    }
                    Instant.parse(item.get("updatedAt").asText());
                    parsed.put(expectedId, new PublicProfile(
                            "FOUND",
                            nullable(item.get("nickName")),
                            nullable(item.get("avatar"))));
                } else {
                    return ProfileBatch.unavailable();
                }
            }
            return new ProfileBatch(true, Map.copyOf(parsed));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ProfileBatch.unavailable();
        }
    }

    private JsonNode post(String path, String authorization, String body, long deadlineNanos, String resource) throws Exception {
        return OutboundContext.govern(governed, resource, () -> executePost(path, authorization, body, deadlineNanos));
    }

    private JsonNode executePost(String path, String authorization, String body, long deadlineNanos) throws Exception {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remaining <= 0) {
            throw new TimeoutException("User client budget exhausted");
        }
        long timeout = Math.min(1000L, remaining);
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofMillis(timeout))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json");
        OutboundContext.injectHeaders(builder, telemetry);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, ignored -> new OriginPolicy.BoundedBody(MAX_BODY_BYTES));
        try {
            HttpResponse<byte[]> response = pending.get(timeout, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Upstream user endpoint returned HTTP " + response.statusCode());
            }
            JsonNode json = OriginPolicy.JSON.readTree(response.body());
            if (json == null) {
                throw new IllegalArgumentException("Empty upstream JSON");
            }
            return json;
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private static long deadlineAfter(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private static boolean text(JsonNode node, String key) {
        return node.has(key) && node.get(key).isTextual();
    }

    private static boolean nullableText(JsonNode node) {
        return node != null && (node.isNull() || (node.isTextual() && node.asText().length() <= 2048));
    }

    private static String nullable(JsonNode node) {
        return node.isNull() ? null : node.asText();
    }
}
