package com.yan.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yan.knowledge.core.retrieval.RetrievalMode;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class InternalKnowledgeOpenApiContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sharedContractMatchesJavaRequestResponseAndRetrievalModes() throws Exception {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path contractPath = workingDirectory.resolve(
                "contracts/knowledge-agent-internal.openapi.json");
        if (!Files.exists(contractPath)) {
            contractPath = workingDirectory.resolve(
                    "../contracts/knowledge-agent-internal.openapi.json").normalize();
        }
        JsonNode contract = objectMapper.readTree(contractPath.toFile());
        JsonNode schemas = contract.path("components").path("schemas");

        InternalKnowledgeController.InternalRetrievalRequest request = objectMapper.treeToValue(
                schemas.path("InternalRetrievalRequest").path("example"),
                InternalKnowledgeController.InternalRetrievalRequest.class);
        InternalKnowledgeController.InternalRetrievalResponse response = objectMapper.treeToValue(
                schemas.path("InternalRetrievalResponse").path("example"),
                InternalKnowledgeController.InternalRetrievalResponse.class);
        List<String> contractModes = objectMapper.convertValue(
                schemas.path("RetrievalMode").path("enum"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));

        assertThat(request.knowledgeBaseId()).isEqualTo(5L);
        assertThat(request.topK()).isEqualTo(10);
        assertThat(request.candidateK()).isEqualTo(30);
        assertThat(response.citations().get(0).documentVersionId()).isEqualTo("version-2");
        assertThat(contractModes).containsExactly(
                Arrays.stream(RetrievalMode.values()).map(Enum::name).toArray(String[]::new));
    }
}
