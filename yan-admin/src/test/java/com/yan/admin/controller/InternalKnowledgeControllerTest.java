package com.yan.admin.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yan.admin.config.AgentInternalSecurityProperties;
import com.yan.admin.filter.AgentServiceAuthenticationFilter;
import com.yan.knowledge.application.KnowledgeQueryFacade;
import com.yan.knowledge.core.domain.KnowledgeCitation;
import com.yan.knowledge.core.retrieval.RetrievalMode;
import com.yan.knowledge.core.retrieval.RetrievalResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalKnowledgeControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsRetrievalWithoutAgentServiceKey() throws Exception {
        AgentInternalSecurityProperties properties = new AgentInternalSecurityProperties();
        properties.setEnabled(true);
        properties.setServiceKey("stage-zero-service-key");
        AgentServiceAuthenticationFilter filter = new AgentServiceAuthenticationFilter(properties);
        KnowledgeQueryFacade queries = mock(KnowledgeQueryFacade.class);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new InternalKnowledgeController(queries))
                .addFilters(filter)
                .build();

        mvc.perform(post("/internal/api/knowledge/retrievals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "retrievalRequestId": "retrieval-1",
                                  "runId": "run-1",
                                  "userId": 3,
                                  "knowledgeBaseId": 5,
                                  "query": "线程池队列满了怎么办？",
                                  "requestedMode": "AUTO"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queries);
    }

    @Test
    void returnsAuthorizedRetrievalWithTraceableCitations() throws Exception {
        AgentInternalSecurityProperties properties = new AgentInternalSecurityProperties();
        properties.setEnabled(true);
        properties.setServiceKey("stage-zero-service-key");
        AgentServiceAuthenticationFilter filter = new AgentServiceAuthenticationFilter(properties);
        KnowledgeQueryFacade queries = mock(KnowledgeQueryFacade.class);
        KnowledgeCitation citation = new KnowledgeCitation(
                "chunk-1", "document-1", "version-2", "线程池", "拒绝策略", "article:7",
                "队列满时执行配置的拒绝策略。", null, 1.5, 0.5, 0.9, false, null);
        when(queries.query(3L, 5L, "线程池队列满了怎么办？", RetrievalMode.AUTO, 5, 15))
                .thenReturn(new KnowledgeQueryFacade.QueryResponse(
                        9L,
                        new RetrievalResult(
                                RetrievalMode.AUTO,
                                RetrievalMode.HYBRID,
                                List.of(citation),
                                false,
                                List.of(),
                                12,
                                "trace-1")));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new InternalKnowledgeController(queries))
                .addFilters(filter)
                .build();

        mvc.perform(post("/internal/api/knowledge/retrievals")
                        .header(AgentServiceAuthenticationFilter.SERVICE_KEY_HEADER,
                                "stage-zero-service-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "retrievalRequestId": "retrieval-1",
                                  "runId": "run-1",
                                  "userId": 3,
                                  "knowledgeBaseId": 5,
                                  "query": "线程池队列满了怎么办？",
                                  "requestedMode": "AUTO",
                                  "topK": 5,
                                  "candidateK": 15
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value(9))
                .andExpect(jsonPath("$.selectedMode").value("HYBRID"))
                .andExpect(jsonPath("$.citations[0].documentVersionId").value("version-2"))
                .andExpect(jsonPath("$.traceId").value("trace-1"));

        verify(queries).query(3L, 5L, "线程池队列满了怎么办？", RetrievalMode.AUTO, 5, 15);
    }

    @Test
    void rejectsAgentRetrievalWhenUserDoesNotOwnKnowledgeBase() throws Exception {
        AgentInternalSecurityProperties properties = new AgentInternalSecurityProperties();
        properties.setEnabled(true);
        properties.setServiceKey("stage-zero-service-key");
        AgentServiceAuthenticationFilter filter = new AgentServiceAuthenticationFilter(properties);
        KnowledgeQueryFacade queries = mock(KnowledgeQueryFacade.class);
        doThrow(new IllegalArgumentException("Knowledge base is not owned by the current user"))
                .when(queries)
                .query(3L, 5L, "private question", RetrievalMode.AUTO, 10, 30);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new InternalKnowledgeController(queries))
                .addFilters(filter)
                .build();

        mvc.perform(post("/internal/api/knowledge/retrievals")
                        .header(AgentServiceAuthenticationFilter.SERVICE_KEY_HEADER,
                                "stage-zero-service-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "retrievalRequestId": "retrieval-2",
                                  "runId": "run-2",
                                  "userId": 3,
                                  "knowledgeBaseId": 5,
                                  "query": "private question",
                                  "requestedMode": "AUTO"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidRetrievalCandidateBudget() throws Exception {
        AgentInternalSecurityProperties properties = new AgentInternalSecurityProperties();
        properties.setEnabled(false);
        KnowledgeQueryFacade queries = mock(KnowledgeQueryFacade.class);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new InternalKnowledgeController(queries))
                .build();

        mvc.perform(post("/internal/api/knowledge/retrievals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "retrievalRequestId": "retrieval-invalid-budget",
                                  "runId": "run-invalid-budget",
                                  "userId": 3,
                                  "knowledgeBaseId": 5,
                                  "query": "private question",
                                  "requestedMode": "AUTO",
                                  "topK": 20,
                                  "candidateK": 10
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(queries);
    }
}
