package com.alibaba.csp.sentinel.dashboard.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "dashboard.nacos.enabled", havingValue = "true")
public class NacosDashboardConfig {
    @Bean(name = "dashboardNacosConfig", destroyMethod = "shutDown")
    ConfigService dashboardNacosConfig(@Value("${dashboard.nacos.server-addr:}") String server,
            @Value("${dashboard.nacos.namespace:}") String namespace,
            @Value("${dashboard.nacos.username:}") String username,
            @Value("${dashboard.nacos.password:}") String password) throws Exception {
        if (server.trim().isEmpty() || namespace.trim().isEmpty())
            throw new IllegalArgumentException("Explicit isolated Nacos address and namespace are required");
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, server);
        properties.put(PropertyKeyConst.NAMESPACE, namespace);
        if (!username.isEmpty()) properties.put(PropertyKeyConst.USERNAME, username);
        if (!password.isEmpty()) properties.put(PropertyKeyConst.PASSWORD, password);
        return NacosFactory.createConfigService(properties);
    }
}
