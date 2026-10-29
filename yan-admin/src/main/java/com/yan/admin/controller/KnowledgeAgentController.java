package com.yan.admin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yan.admin.agent.AgentGatewayClient;
import com.yan.admin.agent.AgentGatewayException;
import com.yan.admin.security.AgentPrincipalTokenService;
import com.yan.domain.ResponseResult;
import com.yan.knowledge.application.KnowledgeBaseFacade;
import com.yan.utils.SecurityUtils;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/blog/knowledge/{knowledgeBaseId}/agent")
public class KnowledgeAgentController {

    private static final Set<String> RETRIEVAL_MODES = Set.of(
            "AUTO", "FULL_CONTEXT", "EXACT", "KEYWORD", "VECTOR", "HYBRID");

    private final KnowledgeBaseFacade knowledgeBases;
    private final AgentPrincipalTokenService principalTokens;
    private final AgentGatewayClient gateway;
    private final ObjectMapper objectMapper;
    private final Supplier<Long> currentUserId;

    @Autowired
    public KnowledgeAgentController(KnowledgeBaseFacade knowledgeBases,
                                    AgentPrincipalTokenService principalTokens,
                                    AgentGatewayClient gateway,
                                    ObjectMapper objectMapper) {
        this(knowledgeBases, principalTokens, gateway, objectMapper, SecurityUtils::getUserId);
    }

    KnowledgeAgentController(KnowledgeBaseFacade knowledgeBases,
                             AgentPrincipalTokenService principalTokens,
                             AgentGatewayClient gateway,
                             ObjectMapper objectMapper,
                             Supplier<Long> currentUserId) {
        this.knowledgeBases = knowledgeBases;
        this.principalTokens = principalTokens;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.currentUserId = currentUserId;
    }

    @PostMapping("/runs")
    public ResponseEntity<ResponseResult<JsonNode>> createRun(
            @PathVariable Long knowledgeBaseId,
            @RequestBody CreateRunRequest request) {
        validateCreateRequest(request);
        Long userId = authorize(knowledgeBaseId);
        String token = principalTokens.issue(userId, knowledgeBaseId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("clientRequestId", request.clientRequestId().strip());
        payload.put("conversationId", request.conversationId().strip());
        payload.put("knowledgeBaseId", knowledgeBaseId);
        payload.put("question", request.question().strip());
        payload.put("requestedMode", normalizeMode(request.requestedMode()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ok(gateway.createRun(token, payload)));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ResponseResult<JsonNode>> getRun(
            @PathVariable Long knowledgeBaseId,
            @PathVariable String runId) {
        String token = tokenFor(knowledgeBaseId);
        return ResponseEntity.ok(ok(gateway.getRun(token, runId)));
    }

    @PostMapping("/runs/{runId}/resume")
    public ResponseEntity<ResponseResult<JsonNode>> resumeRun(
            @PathVariable Long knowledgeBaseId,
            @PathVariable String runId,
            @RequestBody ResumeRunRequest request) {
        if (request == null || blankOrLong(request.answer(), 2000)) {
            throw new AgentRequestException(HttpStatus.BAD_REQUEST, "补充信息不能为空且不能超过 2000 字");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("answer", request.answer().strip());
        return ResponseEntity.ok(ok(
                gateway.resumeRun(tokenFor(knowledgeBaseId), runId, payload)));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ResponseResult<JsonNode>> getConversation(
            @PathVariable Long knowledgeBaseId,
            @PathVariable String conversationId) {
        String token = tokenFor(knowledgeBaseId);
        return ResponseEntity.ok(ok(
                gateway.getConversation(token, conversationId)));
    }

    @ExceptionHandler(AgentGatewayException.class)
    public ResponseEntity<ResponseResult<Void>> handleGatewayFailure(AgentGatewayException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode());
        HttpStatus resolved = status == null ? HttpStatus.BAD_GATEWAY : status;
        return ResponseEntity.status(resolved)
                .body(error(resolved.value(), exception.getMessage()));
    }

    @ExceptionHandler(AgentRequestException.class)
    public ResponseEntity<ResponseResult<Void>> handleRequestFailure(AgentRequestException exception) {
        return ResponseEntity.status(exception.status)
                .body(error(exception.status.value(), exception.getMessage()));
    }

    private String tokenFor(Long knowledgeBaseId) {
        Long userId = authorize(knowledgeBaseId);
        return principalTokens.issue(userId, knowledgeBaseId);
    }

    private Long authorize(Long knowledgeBaseId) {
        Long userId = currentUserId.get();
        try {
            knowledgeBases.requireReadable(userId, knowledgeBaseId);
        } catch (IllegalArgumentException exception) {
            throw new AgentRequestException(HttpStatus.FORBIDDEN, "无权访问该知识库");
        }
        return userId;
    }

    private void validateCreateRequest(CreateRunRequest request) {
        if (request == null
                || blankOrLong(request.clientRequestId(), 64)
                || blankOrLong(request.conversationId(), 26)
                || blankOrLong(request.question(), 2000)) {
            throw new AgentRequestException(HttpStatus.BAD_REQUEST, "问答请求参数无效");
        }
        normalizeMode(request.requestedMode());
    }

    private String normalizeMode(String requestedMode) {
        String mode = requestedMode == null || requestedMode.isBlank()
                ? "AUTO"
                : requestedMode.strip().toUpperCase();
        if (!RETRIEVAL_MODES.contains(mode)) {
            throw new AgentRequestException(HttpStatus.BAD_REQUEST, "不支持的检索模式");
        }
        return mode;
    }

    private boolean blankOrLong(String value, int maxLength) {
        return value == null || value.isBlank() || value.strip().length() > maxLength;
    }

    private ResponseResult<JsonNode> ok(JsonNode data) {
        ResponseResult<JsonNode> response = new ResponseResult<>();
        response.setData(data);
        return response;
    }

    private ResponseResult<Void> error(int code, String message) {
        return new ResponseResult<>(code, message, null);
    }

    public record CreateRunRequest(
            String clientRequestId,
            String conversationId,
            String question,
            String requestedMode) {
    }

    public record ResumeRunRequest(String answer) {
    }

    private static final class AgentRequestException extends RuntimeException {
        private final HttpStatus status;

        private AgentRequestException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }
}
