package com.knode.gateway;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagementMetricsBoundaryTest {
    @Test void onlyDedicatedLoopbackManagementRequestsBypassAuthentication() {
        AccessGate gate = mock(AccessGate.class);
        when(gate.authorize(any())).thenReturn(Mono.empty());
        WebFilterChain chain = exchange -> Mono.empty();
        var filter = new IngressFilter(gate, 19089, 18089);
        filter.filter(exchange("127.0.0.1", 19089, "127.0.0.1"), chain).block();
        verify(gate, never()).authorize(any());
        filter.filter(exchange("127.0.0.1", 18089, "127.0.0.1"), chain).block();
        filter.filter(exchange("127.0.0.1", 19089, "192.0.2.1"), chain).block();
        filter.filter(exchange("192.0.2.1", 19089, "127.0.0.1"), chain).block();
        verify(gate, times(3)).authorize(any());
        clearInvocations(gate);
        new IngressFilter(gate, 18089, 18089).filter(exchange("127.0.0.1", 18089, "127.0.0.1"), chain).block();
        verify(gate).authorize(any());
    }

    private MockServerWebExchange exchange(String local, int port, String remote) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/prometheus")
                .localAddress(new InetSocketAddress(local, port))
                .remoteAddress(new InetSocketAddress(remote, 12345)));
    }
}
