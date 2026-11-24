package com.knode.content.profile;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.content.runtime.OutboundContext;
import com.knode.governance.GovernedCall;
import io.opentelemetry.api.OpenTelemetry;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class UserProfileClient {
    public record Profile(String status, String nickName, String avatar) {}
    public record DetailedProfile(String status, String nickName, String avatar, String profileRevision, String updatedAt) {}
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI origin;
    private final String basic;
    private final GovernedCall governed;
    private final OpenTelemetry telemetry;
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).connectTimeout(Duration.ofMillis(400))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public UserProfileClient(String origin, String serviceId, String secret) {
        this(origin, serviceId, secret, null, null);
    }

    @Autowired
    public UserProfileClient(@Value("${KNODE_USER_ORIGIN:}") String origin,
                             @Value("${KNODE_USER_SERVICE_ID:}") String serviceId,
                             @Value("${KNODE_USER_BOOTSTRAP_SECRET:}") String secret,
                             ObjectProvider<GovernedCall> governedProvider,
                             ObjectProvider<OpenTelemetry> telemetryProvider) {
        this.governed = governedProvider == null ? null : governedProvider.getIfAvailable();
        this.telemetry = telemetryProvider == null ? null : telemetryProvider.getIfAvailable();
        if (origin.isBlank() && serviceId.isBlank() && secret.isBlank()) {
            this.origin=null; this.basic=null; return;
        }
        if (origin.isBlank() || !serviceId.matches("[a-z][a-z0-9-]{0,63}")
                || secret.isBlank() || secret.length()>512) throw new IllegalArgumentException("Invalid user client configuration");
        URI uri;
        try { uri=URI.create(origin); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid user client origin"); }
        String host=uri.getHost(), path=uri.getRawPath();
        boolean secure="https".equalsIgnoreCase(uri.getScheme());
        boolean loopback="http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host));
        if ((!secure && !loopback) || host==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null
                || uri.getRawFragment()!=null || !(path==null || path.isEmpty() || path.equals("/"))
                || uri.getPort() < -1 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Invalid user client origin");
        this.origin=uri;
        this.basic="Basic "+Base64.getEncoder().encodeToString((serviceId+":"+secret).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isConfigured() {
        return origin != null && basic != null;
    }

    public String issueServiceToken(String audience, List<String> permissions, long deadline) throws Exception {
        if (origin == null || basic == null) throw new IllegalStateException("User client unconfigured");
        if (audience == null || !audience.matches("[a-z][a-z0-9-]{0,63}") || permissions == null || permissions.isEmpty() || permissions.size() > 10) {
            throw new IllegalArgumentException("Invalid service token request");
        }
        String payload = JSON.writeValueAsString(Map.of("audience", audience, "permissions", permissions));
        JsonNode token = post("/internal/v1/auth/service-tokens", basic, payload, deadline);
        if (!fields(token, Set.of("accessToken", "tokenType", "expiresIn", "audience", "permissions"))
                || !text(token, "accessToken") || token.get("accessToken").asText().isBlank()
                || token.get("accessToken").asText().length() > 8192
                || token.get("accessToken").asText().codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || !"Bearer".equals(token.path("tokenType").asText())
                || !audience.equals(token.path("audience").asText())
                || !token.path("expiresIn").isIntegralNumber() || !token.path("expiresIn").canConvertToInt()
                || token.path("expiresIn").asInt() < 1 || token.path("expiresIn").asInt() > 300
                || !token.path("permissions").isArray() || token.path("permissions").size() != permissions.size()) {
            throw new IllegalArgumentException("Invalid service token response");
        }
        Set<String> returned = new HashSet<>();
        for (JsonNode p : token.path("permissions")) {
            if (!p.isTextual() || !returned.add(p.asText())) throw new IllegalArgumentException("Invalid service token permissions");
        }
        if (!returned.equals(Set.copyOf(permissions))) throw new IllegalArgumentException("Mismatched service token permissions");
        return token.get("accessToken").asText();
    }

    public DetailedProfile lookupDetailed(String userId) {
        if (!validId(userId) || origin == null) return new DetailedProfile("UNAVAILABLE", null, null, null, null);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
        try {
            String accessToken = issueServiceToken("knode-user", List.of("users:public:read"), deadline);
            String body = JSON.writeValueAsString(Map.of("userIds", List.of(userId)));
            JsonNode result = post("/internal/v1/users/public-profiles:batch", "Bearer " + accessToken, body, deadline);
            if (!fields(result, Set.of("items")) || !result.get("items").isArray() || result.get("items").size() != 1) {
                throw new IllegalArgumentException();
            }
            JsonNode item = result.get("items").get(0);
            if (!item.isObject() || !text(item, "userId") || !text(item, "status") || !userId.equals(item.get("userId").asText())) {
                throw new IllegalArgumentException();
            }
            String status = item.get("status").asText();
            if ("MISSING".equals(status)) {
                if (!fields(item, Set.of("userId", "status"))) throw new IllegalArgumentException();
                return new DetailedProfile("MISSING", null, null, null, null);
            }
            if ("FOUND".equals(status)) {
                if (!fields(item, Set.of("userId", "status", "nickName", "avatar", "profileRevision", "updatedAt"))
                        || !nullableText(item.get("nickName")) || !nullableText(item.get("avatar"))
                        || !text(item, "profileRevision") || !validId(item.get("profileRevision").asText())
                        || !text(item, "updatedAt") || !item.get("updatedAt").asText().endsWith("Z")) {
                    throw new IllegalArgumentException();
                }
                Instant.parse(item.get("updatedAt").asText());
                return new DetailedProfile("FOUND", nullable(item.get("nickName")), nullable(item.get("avatar")),
                        item.get("profileRevision").asText(), item.get("updatedAt").asText());
            }
            throw new IllegalArgumentException();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return new DetailedProfile("UNAVAILABLE", null, null, null, null);
        }
    }

    public Map<String,Profile> lookup(Collection<String> input) {
        LinkedHashSet<String> ids=new LinkedHashSet<>(input);
        if (ids.isEmpty()) return Map.of();
        if (ids.size()>100 || ids.stream().anyMatch(id -> !validId(id))) return unavailable(ids);
        if (origin==null) return unavailable(ids);
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(2000);
        try {
            JsonNode token=post("/internal/v1/auth/service-tokens",basic,
                "{\"audience\":\"knode-user\",\"permissions\":[\"users:public:read\"]}",deadline);
            if (!fields(token,Set.of("accessToken","tokenType","expiresIn","audience","permissions"))
                || !text(token,"accessToken") || token.get("accessToken").asText().isBlank()
                || token.get("accessToken").asText().length()>8192 || token.get("accessToken").asText().codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || !"Bearer".equals(token.path("tokenType").asText())
                || !"knode-user".equals(token.path("audience").asText())
                || !token.path("expiresIn").isIntegralNumber() || !token.path("expiresIn").canConvertToInt()
                || token.path("expiresIn").asInt()<1 || token.path("expiresIn").asInt()>300
                || !token.path("permissions").isArray() || token.path("permissions").size()!=1
                || !"users:public:read".equals(token.path("permissions").get(0).asText()))
                throw new IllegalArgumentException();
            String body=JSON.writeValueAsString(Map.of("userIds",ids));
            JsonNode result=post("/internal/v1/users/public-profiles:batch",
                "Bearer "+token.get("accessToken").asText(),body,deadline);
            if (!fields(result,Set.of("items")) || !result.get("items").isArray()
                    || result.get("items").size()!=ids.size()) throw new IllegalArgumentException();
            Map<String,Profile> profiles=new LinkedHashMap<>();
            for (JsonNode item:result.get("items")) {
                if (!item.isObject() || !text(item,"userId") || !text(item,"status")) throw new IllegalArgumentException();
                String id=item.get("userId").asText(), status=item.get("status").asText();
                if (!validId(id) || !ids.contains(id) || profiles.containsKey(id)) throw new IllegalArgumentException();
                if ("MISSING".equals(status)) {
                    if (!fields(item,Set.of("userId","status"))) throw new IllegalArgumentException();
                    profiles.put(id,new Profile(status,null,null));
                } else if ("FOUND".equals(status)) {
                    if (!fields(item,Set.of("userId","status","nickName","avatar","profileRevision","updatedAt"))
                            || !nullableText(item.get("nickName")) || !nullableText(item.get("avatar"))
                            || !text(item,"profileRevision") || !validId(item.get("profileRevision").asText())
                            || !text(item,"updatedAt") || !item.get("updatedAt").asText().endsWith("Z"))
                        throw new IllegalArgumentException();
                    Instant.parse(item.get("updatedAt").asText());
                    profiles.put(id,new Profile(status,nullable(item.get("nickName")),nullable(item.get("avatar"))));
                } else throw new IllegalArgumentException();
            }
            if (!profiles.keySet().equals(ids)) throw new IllegalArgumentException();
            return profiles;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return unavailable(ids);
        }
    }
    /** Fetch current admin permissions for the already verified user; never cache this result. */
    public Set<String> adminPermissions(String token,String principalId) {
        if (origin==null || token==null || !validId(principalId)) throw new com.knode.auth.AuthFailure(503);
        try {
            return OutboundContext.govern(governed, "feign:knode-user#adminMe", () -> fetchAdminPermissions(token, principalId));
        } catch (com.knode.auth.AuthFailure failure) {
            throw failure;
        } catch (com.knode.governance.GovernanceUnavailableException failure) {
            if (failure.getCause() instanceof com.knode.auth.AuthFailure auth) throw auth;
            throw new com.knode.auth.AuthFailure(503);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new com.knode.auth.AuthFailure(503);
        }
    }
    private Set<String> fetchAdminPermissions(String token,String principalId) throws Exception {
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            HttpRequest.Builder builder=HttpRequest.newBuilder(origin.resolve("/api/admin/auth/me"))
                .timeout(Duration.ofMillis(1000)).header("token",token);
            OutboundContext.injectHeaders(builder, telemetry);
            HttpRequest request=builder.GET().build();
            pending=http.sendAsync(request,ignored -> new BoundedBody());
            HttpResponse<byte[]> response=pending.get(1000,TimeUnit.MILLISECONDS);
            if (response.statusCode()==401 || response.statusCode()==403)
                throw new com.knode.auth.AuthFailure(response.statusCode());
            if (response.statusCode()!=200) throw new com.knode.auth.AuthFailure(503);
            JsonNode root=JSON.readTree(response.body());
            if (!fields(root,Set.of("code","msg","data")) || !root.path("code").isIntegralNumber() || !root.path("code").canConvertToInt()
                    || root.path("code").asInt()!=200 || !text(root,"msg") || !root.path("data").isObject())
                throw new com.knode.auth.AuthFailure(503);
            JsonNode data=root.get("data"), user=data.path("user");
            if (!fields(data,Set.of("roles","permissions","user")) || !user.isObject()
                    || !text(user,"id") || !validId(user.get("id").asText())
                    || !principalId.equals(user.get("id").asText()))
                throw new com.knode.auth.AuthFailure(503);
            checkedStrings(data.get("roles"));
            return checkedStrings(data.get("permissions"));
        } finally { if (pending!=null && !pending.isDone()) pending.cancel(true); }
    }
    private static Set<String> checkedStrings(JsonNode items) {
        if (items==null || !items.isArray() || items.size()>1000) throw new com.knode.auth.AuthFailure(503);
        Set<String> result=new HashSet<>();
        for (JsonNode item:items) {
            if (!item.isTextual() || item.asText().length()>128
                    || !result.add(item.asText())) throw new com.knode.auth.AuthFailure(503);
        }
        return Set.copyOf(result);
    }
    private JsonNode post(String path,String authorization,String body,long deadline) throws Exception {
        String resource = path.contains("service-tokens") ? "feign:knode-user#serviceTokens" : "feign:knode-user#publicProfiles";
        return OutboundContext.govern(governed, resource, () -> executePost(path, authorization, body, deadline));
    }
    private JsonNode executePost(String path,String authorization,String body,long deadline) throws Exception {
        long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
        if (remaining<=0) throw new TimeoutException();
        long timeout=Math.min(1000,remaining);
        HttpRequest.Builder builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofMillis(timeout))
            .header("Authorization",authorization).header("Content-Type","application/json");
        OutboundContext.injectHeaders(builder, telemetry);
        HttpRequest request=builder.POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        CompletableFuture<HttpResponse<byte[]>> pending=http.sendAsync(request,ignored -> new BoundedBody());
        try {
            HttpResponse<byte[]> response=pending.get(timeout,TimeUnit.MILLISECONDS);
            if (response.statusCode()!=200) throw new IllegalArgumentException();
            JsonNode json=JSON.readTree(response.body());
            if (json==null) throw new IllegalArgumentException();
            return json;
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }
    private static boolean fields(JsonNode n,Set<String> required) {
        if (n==null || !n.isObject() || n.size()!=required.size()) return false;
        for (String name:required) if (!n.has(name)) return false;
        return true;
    }
    private static boolean text(JsonNode n,String key) { return n.has(key) && n.get(key).isTextual(); }
    private static boolean nullableText(JsonNode n) { return n!=null && (n.isNull() || n.isTextual() && n.asText().length()<=2048); }
    private static String nullable(JsonNode n) { return n.isNull()?null:n.asText(); }
    private static boolean validId(String id) {
        if (id==null || !id.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(id)>0; } catch (NumberFormatException e) { return false; }
    }
    private static Map<String,Profile> unavailable(Collection<String> ids) {
        Map<String,Profile> out=new LinkedHashMap<>();
        ids.forEach(id -> out.put(id,new Profile("UNAVAILABLE",null,null)));
        return out;
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription s) { subscription=s; s.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk:chunks) {
                if ((long)buffer.size()+chunk.remaining()>3145728) {
                    subscription.cancel(); result.completeExceptionally(new IllegalArgumentException()); return;
                }
                byte[] bytes=new byte[chunk.remaining()]; chunk.get(bytes); buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable ignored) { result.completeExceptionally(new IllegalStateException()); }
        public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
