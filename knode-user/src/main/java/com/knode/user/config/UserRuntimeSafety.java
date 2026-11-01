package com.knode.user.config;

import com.knode.user.web.HealthController;
import java.net.InetAddress;
import org.springframework.boot.actuate.health.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class UserRuntimeSafety {
    public UserRuntimeSafety(Environment env) {
        boolean observation=env.getProperty("knode.observability.enabled",Boolean.class,false);
        if (observation && !env.getRequiredProperty("KNODE_AUTH_ENVIRONMENT").equals(env.getRequiredProperty("knode.observability.environment")))
            throw new IllegalStateException("User authentication and observability environments must match");
        boolean discovery=env.getProperty("spring.cloud.nacos.discovery.enabled",Boolean.class,false);
        if (discovery) {
            for (String key:new String[]{"spring.cloud.nacos.discovery.server-addr","spring.cloud.nacos.discovery.namespace","spring.cloud.nacos.discovery.group"})
                if (env.getProperty(key,"").isBlank()) throw new IllegalStateException("Explicit User discovery address, namespace and group required");
            if ("public".equalsIgnoreCase(env.getProperty("spring.cloud.nacos.discovery.namespace")))
                throw new IllegalStateException("An isolated User discovery namespace is required");
            boolean hasUser=!env.getProperty("spring.cloud.nacos.discovery.username","").isBlank();
            boolean hasPassword=!env.getProperty("spring.cloud.nacos.discovery.password","").isBlank();
            if (hasUser!=hasPassword) throw new IllegalStateException("Provide both Nacos credential values");
        }
        String imports=env.getProperty("spring.config.import","");
        if (imports.toLowerCase(java.util.Locale.ROOT).contains("nacos:"))
            throw new IllegalStateException("Use the fixed User relay config instead of arbitrary Nacos config imports");
        int management=env.getProperty("management.server.port",Integer.class,19092);
        int service=env.getProperty("server.port",Integer.class,18090);
        if (management<0 || management>65535 || (management!=0 && management==service))
            throw new IllegalStateException("An independent User management port is required");
        try {
            if (!InetAddress.getByName(env.getProperty("management.server.address","127.0.0.1")).isLoopbackAddress())
                throw new IllegalStateException("User management address must be loopback");
        } catch (java.net.UnknownHostException e) {throw new IllegalStateException("Invalid management address");}
    }
    @Bean
    org.springframework.http.server.observation.ServerRequestObservationConvention userHttpObservationConvention() {
        return new org.springframework.http.server.observation.DefaultServerRequestObservationConvention() {
            @Override public io.micrometer.common.KeyValues getHighCardinalityKeyValues(
                    org.springframework.http.server.observation.ServerRequestObservationContext context) {
                // Default convention emits the full URL, including path identifiers and port.
                return io.micrometer.common.KeyValues.empty();
            }
        };
    }
    @Bean
    io.micrometer.observation.ObservationFilter userObservationPrivacy() {
        return context -> {
            if (context instanceof org.springframework.http.server.observation.ServerRequestObservationContext) {
                for (String key:new String[]{"http.url","url.full","url.path","http.target","server.port","net.host.port"}) {
                    context.removeHighCardinalityKeyValue(key);context.removeLowCardinalityKeyValue(key);
                }
            }
            return context;
        };
    }
    @Bean("userReadiness")
    HealthIndicator userReadiness(HealthController controller,
            org.springframework.beans.factory.ObjectProvider<com.knode.governance.GovernanceRules> governance) {
        return () -> {
            try {return Health.up().withDetails(controller.ready()).build();}
            catch (RuntimeException unavailable) {
                var rules=governance.getIfAvailable();
                return Health.outOfService().withDetail("reason","User dependency unavailable")
                        .withDetail("governanceRules",rules==null?"DISABLED":rules.health().getStatus().getCode()).build();
            }
        };
    }
}
