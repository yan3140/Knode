package com.knode.user.config;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Checks the actual connection and this instance's published registration, not only the toggle. */
@Component
public final class UserDiscoveryStatus implements HealthIndicator {
    private final Environment env;
    private final ObjectProvider<NacosServiceManager> manager;
    private final ObjectProvider<NacosDiscoveryProperties> properties;
    public UserDiscoveryStatus(Environment env,ObjectProvider<NacosServiceManager> manager,ObjectProvider<NacosDiscoveryProperties> properties) {
        this.env=env;this.manager=manager;this.properties=properties;
    }
    @Override public Health health() {
        if (!env.getProperty("spring.cloud.nacos.discovery.enabled",Boolean.class,false))
            return Health.up().withDetail("status","DISABLED").build();
        try {
            var m=manager.getIfAvailable();var p=properties.getIfAvailable();
            if (m==null || p==null || !"UP".equals(m.getNamingService().getServerStatus()))
                return Health.outOfService().withDetail("reason","Nacos discovery unavailable").build();
            boolean registered=m.getNamingService().getAllInstances(env.getRequiredProperty("spring.application.name"),p.getGroup()).stream()
                    .anyMatch(i -> i.isHealthy() && i.isEnabled() && i.getPort()==p.getPort() && p.getIp().equals(i.getIp()));
            return registered?Health.up().build():Health.outOfService().withDetail("reason","User registration unconfirmed").build();
        } catch (Exception e) {return Health.outOfService().withDetail("reason","Nacos discovery unavailable").build();}
    }
}
