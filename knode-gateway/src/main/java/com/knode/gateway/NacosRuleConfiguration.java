package com.knode.gateway;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "knode.governance.nacos.enabled", havingValue = "true")
public class NacosRuleConfiguration {
    @Bean(destroyMethod = "")
    ConfigService governanceConfigService(
            @Value("${knode.governance.nacos.server-addr:}") String server,
            @Value("${knode.governance.nacos.namespace:}") String namespace,
            @Value("${knode.governance.nacos.username:}") String username,
            @Value("${knode.governance.nacos.password:}") String password) throws NacosException {
        if (server.isBlank() || namespace.isBlank())
            throw new IllegalStateException("Explicit isolated Nacos server and namespace are required");
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, server);
        props.put(PropertyKeyConst.NAMESPACE, namespace);
        if (!username.isBlank()) props.put(PropertyKeyConst.USERNAME, username);
        if (!password.isBlank()) props.put(PropertyKeyConst.PASSWORD, password);
        return NacosFactory.createConfigService(props);
    }
}
