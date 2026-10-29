package com.yan.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yan.admin.agent.AgentGatewayClient;
import com.yan.admin.agent.AgentGatewayException;
import com.yan.admin.security.AgentPrincipalTokenService;
import com.yan.knowledge.application.KnowledgeBaseFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeAgentControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KnowledgeBaseFacade knowledgeBases;
    private AgentPrincipalTokenService principalTokens;
    private AgentGatewayClient gateway;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        knowledgeBases = mock(KnowledgeBaseFacade.class);
        principalTokens = mock(AgentPrincipalTokenService.class);
        gateway = mock(AgentGatewayClient.class);
        KnowledgeAgentController controller = new KnowledgeAgentController(
                knowledgeBases,
                principalTokens,
                gateway,
                objectMapper,
                () -> 7L);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createsRunUsingServerControlledUserAndKnowledgeBaseScope() throws Exception {
        when(principalTokens.issue(7L, 9L)).thenReturn("principal-token");
        JsonNode response = objectMapper.readTree("""
                {"runId":"run-1","conversationId":"conversation-1","status":"QUEUED"}
                """);
        when(gateway.createRun(eq("principal-token"), any(JsonNode.class))).thenReturn(response);

        mvc.perform(post("/api/blog/knowledge/9/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"request-1",
                                  "conversationId":"conversation-1",
                                  "question":"MySQL 索引为什么会失效？",
                                  "requestedMode":"hybrid"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.runId").value("run-1"));

        verify(knowledgeBases).requireReadable(7L, 9L);
        verify(principalTokens).issue(7L, 9L);
        verify(gateway).createRun(eq("principal-token"), any(JsonNode.class));
    }

    @Test
    void rejectsKnowledgeBaseOutsideCurrentUserScope() throws Exception {
        when(knowledgeBases.requireReadable(7L, 9L))
                .thenThrow(new IllegalArgumentException("not owner"));

        mvc.perform(post("/api/blog/knowledge/9/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"request-1",
                                  "conversationId":"conversation-1",
                                  "question":"private question"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.msg").value("无权访问该知识库"));

        verifyNoInteractions(principalTokens, gateway);
    }

    @Test
    void returnsServiceUnavailableWhenAgentCannotBeReached() throws Exception {
        when(principalTokens.issue(7L, 9L)).thenReturn("principal-token");
        when(gateway.createRun(eq("principal-token"), any(JsonNode.class)))
                .thenThrow(new AgentGatewayException(503, "Agent 服务暂时不可用"));

        mvc.perform(post("/api/blog/knowledge/9/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"request-1",
                                  "conversationId":"conversation-1",
                                  "question":"MySQL 如何避免回表？"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.msg").value("Agent 服务暂时不可用"));
    }
}
