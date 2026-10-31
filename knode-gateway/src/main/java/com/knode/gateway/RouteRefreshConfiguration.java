package com.knode.gateway;

import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Configuration;

@Configuration
class RouteRefreshConfiguration {
    private final ApplicationEventPublisher publisher;
    RouteRefreshConfiguration(ApplicationEventPublisher publisher) { this.publisher = publisher; }
    @EventListener(RefreshScopeRefreshedEvent.class)
    void refreshRoutes() { publisher.publishEvent(new RefreshRoutesEvent(this)); }
}
