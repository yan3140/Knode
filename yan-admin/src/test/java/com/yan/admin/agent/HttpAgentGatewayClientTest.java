package com.yan.admin.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yan.admin.config.AgentGatewayProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpAgentGatewayClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsJsonAndPrincipalTokenOverHttp11() throws IOException {
        AtomicReference<String> protocol = new AtomicReference<>();
        AtomicReference<String> principal = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/agent/runs", exchange -> {
            protocol.set(exchange.getProtocol());
            principal.set(exchange.getRequestHeaders().getFirst("X-Principal-Context"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"runId\":\"run-1\",\"status\":\"QUEUED\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        AgentGatewayProperties properties = new AgentGatewayProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        ObjectMapper objectMapper = new ObjectMapper();
        HttpAgentGatewayClient client = new HttpAgentGatewayClient(properties, objectMapper);
        JsonNode request = objectMapper.createObjectNode()
                .put("conversationId", "conversation-1")
                .put("knowledgeBaseId", 3);

        JsonNode response = client.createRun("principal-token", request);

        assertThat(response.path("runId").asText()).isEqualTo("run-1");
        assertThat(protocol.get()).isEqualTo("HTTP/1.1");
        assertThat(principal.get()).isEqualTo("principal-token");
        assertThat(objectMapper.readTree(body.get())).isEqualTo(request);
    }
}
