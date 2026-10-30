package com.knode.gateway;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/** Fail closed until the shared authentication and public route contracts are available. */
@Component
public class AccessGate {
    public boolean allowed(ServerHttpRequest request) {
        return false;
    }
}
