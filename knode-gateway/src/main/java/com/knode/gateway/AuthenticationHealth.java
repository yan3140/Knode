package com.knode.gateway;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("authentication")
public class AuthenticationHealth implements ReactiveHealthIndicator {
    private final ObjectProvider<AuthMaterial> materials;
    private final ObjectProvider<SessionReader> readers;
    public AuthenticationHealth(ObjectProvider<AuthMaterial> materials, ObjectProvider<SessionReader> readers) {
        this.materials = materials; this.readers = readers;
    }
    @Override public Mono<Health> health() {
        AuthMaterial material = materials.getIfAvailable();
        SessionReader reader = readers.getIfAvailable();
        if (material == null || reader == null) return Mono.just(Health.outOfService().build());
        return reader.checkAvailability().timeout(material.timeout()).thenReturn(Health.up().build())
                .onErrorReturn(Health.outOfService().build());
    }
}
