package com.knode.gateway;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    private static final List<String> INTERNAL_HEADERS = List.of("Authorization", "X-Principal-Context", "X-Agent-Service-Key", "X-User-Id", "X-User-Scope", "X-Service-Id");
    private final AccessGate accessGate;
    public IngressFilter(AccessGate accessGate) { this.accessGate = accessGate; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (!PathBoundary.isSafe(exchange.getRequest().getURI())) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        List<String> ids = exchange.getRequest().getHeaders().getOrEmpty("X-Request-Id");
        List<String> tokens = exchange.getRequest().getHeaders().getOrEmpty("token");
        boolean duplicateIdentity = INTERNAL_HEADERS.stream()
                .anyMatch(name -> exchange.getRequest().getHeaders().getOrEmpty(name).size() > 1);
        if (ids.size() > 1 || tokens.size() > 1 || duplicateIdentity
                || (tokens.size() == 1 && tokens.get(0).contains(","))) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }
        String id = ids.size() == 1 && REQUEST_ID.matcher(ids.get(0)).matches() ? ids.get(0) : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Request-Id", id);
        var request = exchange.getRequest().mutate().headers(headers -> {
            INTERNAL_HEADERS.forEach(headers::remove);
            headers.set("X-Request-Id", id);
        }).build();
        boolean management = path.equals("/actuator/health") || path.startsWith("/actuator/health/");
        if (!management && !accessGate.allowed(request)) {
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = "{\"code\":500,\"msg\":\"Authentication is not ready\"}".getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        }
        return chain.filter(exchange.mutate().request(request).build());
    }
}
