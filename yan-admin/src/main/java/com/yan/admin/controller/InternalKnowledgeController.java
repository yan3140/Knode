package com.yan.admin.controller;

import com.yan.knowledge.application.KnowledgeQueryFacade;
import com.yan.knowledge.core.domain.KnowledgeCitation;
import com.yan.knowledge.core.retrieval.DegradationReason;
import com.yan.knowledge.core.retrieval.RetrievalMode;
import com.yan.knowledge.core.retrieval.RetrievalResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/api/knowledge")
public class InternalKnowledgeController {

    private final KnowledgeQueryFacade queries;

    public InternalKnowledgeController(KnowledgeQueryFacade queries) {
        this.queries = queries;
    }

    @PostMapping("/retrievals")
    public InternalRetrievalResponse retrieve(@RequestBody InternalRetrievalRequest request) {
        validate(request);
        RetrievalMode requestedMode = request.requestedMode() == null
                ? RetrievalMode.AUTO
                : request.requestedMode();
        KnowledgeQueryFacade.QueryResponse query;
        try {
            query = queries.query(
                    request.userId(),
                    request.knowledgeBaseId(),
                    request.query().strip(),
                    requestedMode,
                    topK(request),
                    candidateK(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Knowledge base access denied",
                    exception);
        }
        return InternalRetrievalResponse.from(query);
    }

    private void validate(InternalRetrievalRequest request) {
        if (request == null
                || request.retrievalRequestId() == null
                || request.retrievalRequestId().isBlank()
                || request.runId() == null
                || request.runId().isBlank()
                || request.userId() == null
                || request.userId() < 1
                || request.knowledgeBaseId() == null
                || request.knowledgeBaseId() < 1
                || request.query() == null
                || request.query().isBlank()
                || request.query().strip().length() > 2000
                || topK(request) < 1
                || topK(request) > 50
                || candidateK(request) < topK(request)
                || candidateK(request) > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid retrieval request");
        }
    }

    private int topK(InternalRetrievalRequest request) {
        return request.topK() == null ? 10 : request.topK();
    }

    private int candidateK(InternalRetrievalRequest request) {
        return request.candidateK() == null ? 30 : request.candidateK();
    }

    public record InternalRetrievalRequest(
            String retrievalRequestId,
            String runId,
            Long userId,
            Long knowledgeBaseId,
            String query,
            RetrievalMode requestedMode,
            Integer topK,
            Integer candidateK) {
    }

    public record InternalRetrievalResponse(
            Long queryId,
            RetrievalMode requestedMode,
            RetrievalMode selectedMode,
            List<KnowledgeCitation> citations,
            boolean degraded,
            List<DegradationReason> degradationReasons,
            long totalElapsedMillis,
            String traceId) {

        static InternalRetrievalResponse from(KnowledgeQueryFacade.QueryResponse query) {
            RetrievalResult result = query.result();
            return new InternalRetrievalResponse(
                    query.queryId(),
                    result.requestedMode(),
                    result.selectedMode(),
                    result.citations(),
                    result.degraded(),
                    result.degradationReasons(),
                    result.totalElapsedMillis(),
                    result.traceId());
        }
    }
}
