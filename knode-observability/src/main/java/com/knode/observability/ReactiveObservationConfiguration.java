package com.knode.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

@AutoConfiguration
@ConditionalOnProperty(name = "knode.observability.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ReactiveObservationConfiguration {
    @Bean
    ObservationFilter gatewayRequestCorrelation() {
        return context -> {
            if (context instanceof ServerRequestObservationContext http && http.getResponse() != null) {
                String id = http.getResponse().getHeaders().getFirst("X-Request-Id");
                if (RequestCorrelation.valid(id)) context.addHighCardinalityKeyValue(KeyValue.of("knode.request_id", id));
            }
            return context;
        };
    }
}
