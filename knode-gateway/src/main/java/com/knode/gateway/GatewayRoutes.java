package com.knode.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.context.config.annotation.RefreshScope;

@Configuration
public class GatewayRoutes {
    @Bean
    @RefreshScope
    RouteLocator routes(RouteLocatorBuilder b,
            @Value("${knode.gateway.user-uri:lb://knode-user}") String user,
            @Value("${knode.gateway.content-uri:lb://knode-content}") String content,
            @Value("${knode.gateway.community-uri:lb://knode-community}") String community,
            @Value("${knode.gateway.search-uri:lb://knode-search}") String search,
            @Value("${knode.gateway.knowledge-uri:lb://knode-knowledge}") String knowledge) {
        return b.routes()
            .route("profile-content", r -> r.order(-20).path("/api/blog/user/profile", "/api/blog/user/profile/**").uri(content))
            .route("admin-search", r -> r.order(-20).path("/api/admin/content/search", "/api/admin/content/search/**").uri(search))
            .route("user", r -> r.path("/api/blog/auth", "/api/blog/auth/**", "/api/admin/auth", "/api/admin/auth/**",
                    "/api/blog/user", "/api/blog/user/**", "/api/admin/system/user", "/api/admin/system/user/**",
                    "/api/admin/system/role", "/api/admin/system/role/**", "/api/admin/system/menu", "/api/admin/system/menu/**").uri(user))
            .route("blog-content", r -> r.path("/api/blog/article", "/api/blog/article/**", "/api/blog/category", "/api/blog/category/**",
                    "/api/blog/tag", "/api/blog/tag/**", "/api/blog/comment", "/api/blog/comment/**", "/api/blog/link", "/api/blog/link/**").uri(content))
            .route("admin-content", r -> r.path("/api/admin/content", "/api/admin/content/**", "/api/admin/upload",
                    "/api/admin/ops/outbox", "/api/admin/ops/outbox/**").uri(content))
            .route("community", r -> r.path("/api/blog/follow", "/api/blog/follow/**", "/api/blog/notification", "/api/blog/notification/**").uri(community))
            .route("search", r -> r.path("/api/blog/search").uri(search))
            .route("knowledge", r -> r.path("/api/blog/knowledge", "/api/blog/knowledge/**").uri(knowledge))
            .build();
    }
}
