package com.knode.search.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.AuthFailure;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.auth.UserTokenVerifier;
import com.knode.search.config.SearchClientsProperties;
import com.knode.search.metrics.SearchMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(SearchAuthProperties.class)
public class SearchAuthRuntimeConfig {

    @Bean
    @ConditionalOnMissingBean(AuthSessionStore.class)
    @ConditionalOnProperty(prefix = "knode.search.auth.session.redis", name = "enabled", havingValue = "true")
    public LettuceAuthSessionStore lettuceAuthSessionStore(
            SearchAuthProperties properties,
            @Value("${knode.environment:local}") String environment) {
        return new LettuceAuthSessionStore(properties.getSession().getRedis(), environment);
    }

    @Bean
    @ConditionalOnMissingBean(AuthSessionStore.class)
    public AuthSessionStore failClosedAuthSessionStore() {
        return sid -> {
            throw new AuthFailure(503);
        };
    }

    @Bean
    @ConditionalOnMissingBean(AdminAuthorityClient.class)
    public HttpAdminAuthorityClient httpAdminAuthorityClient(
            SearchClientsProperties clientsProperties,
            SearchAuthProperties authProperties) {
        return new HttpAdminAuthorityClient(clientsProperties, authProperties);
    }

    @Bean
    public FilterRegistrationBean<SearchSecurityFilter> searchSecurityFilterRegistration(
            SearchAuthProperties authProperties,
            AuthSessionStore authSessionStore,
            AdminAuthorityClient adminAuthorityClient,
            SearchMetrics searchMetrics,
            ObjectMapper objectMapper,
            Clock clock,
            ObjectProvider<UserTokenVerifier> customUserTokenVerifier,
            ObjectProvider<ServiceTokenVerifier> customServiceTokenVerifier) {
        Map<String, RSAPublicKey> userKeys = TrustedPublicKeyLoader.loadUserKeys(authProperties);
        Map<String, RSAPublicKey> serviceKeys = TrustedPublicKeyLoader.loadServiceKeys(authProperties, userKeys);

        UserTokenVerifier userVerifier = customUserTokenVerifier.getIfAvailable(() ->
                userKeys.isEmpty() ? null : new UserTokenVerifier(userKeys, clock));
        ServiceTokenVerifier serviceVerifier = customServiceTokenVerifier.getIfAvailable(() ->
                serviceKeys.isEmpty() ? null : new ServiceTokenVerifier(serviceKeys, clock));

        SearchSecurityFilter filter = new SearchSecurityFilter(
                userVerifier,
                serviceVerifier,
                authSessionStore,
                adminAuthorityClient,
                authProperties,
                searchMetrics,
                objectMapper
        );
        FilterRegistrationBean<SearchSecurityFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        reg.addUrlPatterns(
                "/internal/v1/ops/consumers/*",
                "/api/admin/content/search/*",
                "/content/search/*"
        );
        return reg;
    }
}
