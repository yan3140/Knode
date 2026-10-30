package com.knode.gateway;

import com.knode.auth.AuthFailure;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class IngressFilter implements WebFilter, Ordered {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final List<String> ID_PREFIXES = List.of("x-internal-", "x-user-", "x-principal-", "x-service-", "x-identity-", "x-authenticated-");
    private final AccessGate accessGate;
    public IngressFilter(AccessGate accessGate) { this.accessGate = accessGate; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    private boolean untrustedHeader(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return ID_PREFIXES.stream().anyMatch(n::startsWith) || n.equals("authorization") ||
                n.equals("x-agent-service-key") || n.equals("x-forwarded-user") ||
                n.equals("x-remote-user") || n.equals("x-auth-request-user") ||
                n.equals("x-real-ip") || n.equals("forwarded") || n.startsWith("x-forwarded-");
    }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (!PathBoundary.isSafe(exchange.getRequest().getURI())) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        List<String> ids = exchange.getRequest().getHeaders().getOrEmpty("X-Request-Id");
        List<String> tokens = exchange.getRequest().getHeaders().getOrEmpty("token");
        boolean duplicateIdentity = exchange.getRequest().getHeaders().entrySet().stream()
                .anyMatch(entry -> untrustedHeader(entry.getKey()) && entry.getValue().size() > 1);
        if (ids.size() > 1 || tokens.size() > 1 || duplicateIdentity || (tokens.size() == 1 && tokens.get(0).contains(","))) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }
        String id = ids.size() == 1 && REQUEST_ID.matcher(ids.get(0)).matches() ? ids.get(0) : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Request-Id", id);
        boolean credential = accessGate.credentialEndpoint(exchange.getRequest());
        var request = exchange.getRequest().mutate().headers(headers -> {
            for (String name : new ArrayList<>(headers.keySet())) if (untrustedHeader(name)) headers.remove(name);
            if (credential) headers.remove("token");
            headers.set("X-Request-Id", id);
        }).build();
        boolean management = path.equals("/actuator/health") || path.startsWith("/actuator/health/");
        if (management) return chain.filter(exchange.mutate().request(request).build());
        return accessGate.authorize(request)
                .then(Mono.defer(() -> chain.filter(exchange.mutate().request(request).build())))
                .onErrorResume(AuthFailure.class, error -> {
                    exchange.getResponse().setStatusCode(HttpStatus.valueOf(error.status()));
                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    int businessCode = error.status() == 503 ? 500 : error.status();
                    byte[] body = ("{\"code\":" + businessCode + ",\"msg\":\"" + error.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                    return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
                });
    }
}
