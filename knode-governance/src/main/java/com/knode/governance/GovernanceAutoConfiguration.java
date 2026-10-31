package com.knode.governance;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@ConditionalOnProperty(name = "knode.governance.enabled", havingValue = "true")
public class GovernanceAutoConfiguration {
    @Bean(name = "knodeGovernanceConfig", destroyMethod = "")
    @ConditionalOnMissingBean(name = "knodeGovernanceConfig")
    ConfigService configService(@Value("${knode.governance.nacos.server-addr:}") String server,
            @Value("${knode.governance.nacos.namespace:}") String namespace,
            @Value("${knode.governance.nacos.username:}") String username,
            @Value("${knode.governance.nacos.password:}") String password) throws Exception {
        if (server.isBlank() || namespace.isBlank())
            throw new IllegalStateException("Explicit governance Nacos address and namespace required");
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, server);
        props.put(PropertyKeyConst.NAMESPACE, namespace);
        if (!username.isBlank()) props.put(PropertyKeyConst.USERNAME, username);
        if (!password.isBlank()) props.put(PropertyKeyConst.PASSWORD, password);
        return NacosFactory.createConfigService(props);
    }

    @Bean(name = "governanceRules")
    GovernanceRules governanceRules(@Qualifier("knodeGovernanceConfig") ConfigService config, ObjectMapper mapper,
            @Value("${spring.application.name:}") String app,
            @Value("${knode.governance.nacos.group:KNODE_SENTINEL}") String group,
            @Value("${knode.governance.nacos.retry-min-ms:1000}") long min,
            @Value("${knode.governance.nacos.retry-max-ms:30000}") long max) {
        return new GovernanceRules(config, mapper, app, group, min, max);
    }

    @Bean
    @ConditionalOnProperty(name = "csp.sentinel.dashboard.server")
    org.springframework.boot.ApplicationRunner sentinelTransportStart() {
        return args -> com.alibaba.csp.sentinel.init.InitExecutor.doInit();
    }

    @Bean
    GovernedCall governedCall() { return new GovernedCall(); }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    WebMvcConfigurer governanceMvc() {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new GovernanceHttpInterceptor()).excludePathPatterns("/actuator/**");
            }
        };
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    GovernanceExceptionHandler governanceExceptionHandler() { return new GovernanceExceptionHandler(); }
}
