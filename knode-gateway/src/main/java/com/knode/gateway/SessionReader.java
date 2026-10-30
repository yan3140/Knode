package com.knode.gateway;
import reactor.core.publisher.Mono;
public interface SessionReader {
    Mono<String> get(String sid);
    Mono<Void> checkAvailability();
}
