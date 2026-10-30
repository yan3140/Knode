package com.knode.gateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class GatewayRoutes {
    @Bean
    RouteLocator routes(RouteLocatorBuilder b, @Value("${knode.gateway.user-uri:lb://knode-user}") String user,
        @Value("${knode.gateway.content-uri:lb://knode-content}") String content,
        @Value("${knode.gateway.search-uri:lb://knode-search}") String search) {
        return b.routes()
            .route("profile-content", r -> r.order(-20).path("/api/blog/user/profile", "/api/blog/user/profile/**").uri(content))
            .route("admin-search", r -> r.order(-20).path("/api/admin/content/search", "/api/admin/content/search/**").uri(search))
            .route("user", r -> r.order(0).path("/api/blog/user", "/api/blog/user/**").uri(user))
            .route("admin-content", r -> r.order(0).path("/api/admin/content", "/api/admin/content/**").uri(content))
            .build();
    }
}
