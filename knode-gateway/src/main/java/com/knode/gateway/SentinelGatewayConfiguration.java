package com.knode.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

@Configuration
public class SentinelGatewayConfiguration {
    @Bean
    @Order(-1)
    GlobalFilter sentinelGatewayFilter() { return new SentinelGatewayFilter(); }

    @Bean
    @Order(-1)
    SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler(
            ObjectProvider<List<ViewResolver>> views, ServerCodecConfigurer codecs) {
        GatewayCallbackManager.setBlockHandler((exchange, error) ->
                ServerResponse.status(429).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"code\":429,\"msg\":\"Too many requests\"}"));
        return new SentinelGatewayBlockExceptionHandler(views.getIfAvailable(Collections::emptyList), codecs);
    }
}
