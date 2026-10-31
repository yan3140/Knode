package com.knode.gateway;

import com.alibaba.csp.sentinel.init.InitExecutor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "csp.sentinel.dashboard.server")
class SentinelTransportConfiguration {
    @Bean ApplicationRunner sentinelTransportStart() {
        return args -> InitExecutor.doInit();
    }
}
