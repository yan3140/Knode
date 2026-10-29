package com.yan.admin.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yan.admin.config.AgentGatewayProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HttpAgentGatewayClient implements AgentGatewayClient {

    private static final String PRINCIPAL_HEADER = "X-Principal-Context";
    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AgentGatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI baseUri;

    @Autowired
    public HttpAgentGatewayClient(AgentGatewayProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(positiveDuration(properties.getConnectTimeoutSeconds(), "connect timeout"))
                .build());
    }

    HttpAgentGatewayClient(AgentGatewayProperties properties,
                           ObjectMapper objectMapper,
                           HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        String configuredBaseUrl = properties.getBaseUrl();
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            throw new IllegalStateException("Agent gateway base URL must not be blank");
        }
        this.baseUri = URI.create(configuredBaseUrl.endsWith("/")
                ? configuredBaseUrl
                : configuredBaseUrl + "/");
    }

    @Override
    public JsonNode createRun(String principalToken, JsonNode request) {
        return exchange("internal/v1/agent/runs", "POST", principalToken, request);
    }

    @Override
    public JsonNode getRun(String principalToken, String runId) {
        return exchange("internal/v1/agent/runs/" + resourceId(runId), "GET", principalToken, null);
    }

    @Override
    public JsonNode resumeRun(String principalToken, String runId, JsonNode request) {
        return exchange(
                "internal/v1/agent/runs/" + resourceId(runId) + "/resume",
                "POST",
                principalToken,
                request);
    }

    @Override
    public JsonNode getConversation(String principalToken, String conversationId) {
        return exchange(
                "internal/v1/agent/conversations/" + resourceId(conversationId),
                "GET",
                principalToken,
                null);
    }

    private JsonNode exchange(String path, String method, String principalToken, JsonNode requestBody) {
        if (principalToken == null || principalToken.isBlank()) {
            throw new IllegalArgumentException("Agent principal token must not be blank");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(positiveDuration(properties.getRequestTimeoutSeconds(), "request timeout"))
                .header("Accept", "application/json")
                .header(PRINCIPAL_HEADER, principalToken);
        if (requestBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamFailure(response.statusCode(), response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentGatewayException(503, "Agent 服务请求已中断", exception);
        } catch (IOException exception) {
            throw new AgentGatewayException(503, "Agent 服务暂时不可用", exception);
        }
    }

    private AgentGatewayException upstreamFailure(int upstreamStatus, String body) {
        int clientStatus = switch (upstreamStatus) {
            case 400, 404, 409 -> upstreamStatus;
            case 422 -> 400;
            case 503, 504 -> 503;
            default -> 502;
        };
        String message = "Agent 服务请求失败";
        try {
            JsonNode payload = objectMapper.readTree(body);
            JsonNode detail = payload.path("detail");
            JsonNode candidate = detail.isObject() ? detail.path("message") : detail;
            if (candidate.isTextual() && !candidate.asText().isBlank()) {
                message = candidate.asText();
            }
        } catch (JsonProcessingException ignored) {
            // Preserve a stable user-facing message when the upstream body is not JSON.
        }
        return new AgentGatewayException(clientStatus, message);
    }

    private static String resourceId(String value) {
        if (value == null || !RESOURCE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Agent resource id");
        }
        return value;
    }

    private static Duration positiveDuration(int seconds, String name) {
        if (seconds < 1) {
            throw new IllegalStateException("Agent gateway " + name + " must be positive");
        }
        return Duration.ofSeconds(seconds);
    }
}
