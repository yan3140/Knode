package com.knode.gateway;

import com.knode.auth.AuthFailure;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class AccessGate {
    private final ObjectProvider<AuthMaterial> materials;
    private final ObjectProvider<SessionReader> readers;
    public AccessGate(ObjectProvider<AuthMaterial> materials, ObjectProvider<SessionReader> readers) {
        this.materials = materials;
        this.readers = readers;
    }
    public boolean credentialEndpoint(ServerHttpRequest request) {
        String p = request.getURI().getPath();
        return HttpMethod.POST.equals(request.getMethod()) &&
                (p.equals("/api/blog/auth/login") || p.equals("/api/admin/auth/login") || p.equals("/api/blog/user/register"));
    }
    public Mono<Void> authorize(ServerHttpRequest request) {
        if (credentialEndpoint(request)) return Mono.empty();
        String path = request.getURI().getPath();
        String scope = path.startsWith("/api/admin/") ? "admin" : path.startsWith("/api/blog/") ? "blog" : null;
        if (scope == null) return Mono.error(new AuthFailure(401));
        String token = request.getHeaders().getFirst("token");
        boolean publicEndpoint = anonymous(request, path);
        if (token == null && publicEndpoint) return Mono.empty();
        AuthMaterial material = materials.getIfAvailable();
        SessionReader reader = readers.getIfAvailable();
        if (material == null || reader == null) return Mono.error(new AuthFailure(503));
        if (token == null || token.isBlank()) return Mono.error(new AuthFailure(401));
        return Mono.fromCallable(() -> material.verifier().verifyToken(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(verified -> reader.get(verified.sid()).timeout(material.timeout())
                        .map(SessionCodec::decode).map(Optional::of).defaultIfEmpty(Optional.<Session>empty())
                        .map(session -> material.verifier().validateSession(verified, session, publicEndpoint ? verified.scope() : scope)))
                .onErrorMap(error -> error instanceof AuthFailure ? error : new AuthFailure(503))
                .then();
    }
    private boolean anonymous(ServerHttpRequest request, String p) {
        if (HttpMethod.GET.equals(request.getMethod())) {
            for (String prefix : new String[]{"/api/blog/article", "/api/blog/category", "/api/blog/tag",
                    "/api/blog/link", "/api/blog/comment", "/api/blog/search", "/api/blog/user/profile"}) {
                if (p.equals(prefix) || p.startsWith(prefix + "/")) return true;
            }
            return p.matches("/api/blog/follow/[^/]+/following");
        }
        return HttpMethod.PUT.equals(request.getMethod()) &&
                p.startsWith("/api/blog/article/updateViewCount/");
    }
}
