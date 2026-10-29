package com.yan.admin.agent;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgentGatewayClient {

    JsonNode createRun(String principalToken, JsonNode request);

    JsonNode getRun(String principalToken, String runId);

    JsonNode resumeRun(String principalToken, String runId, JsonNode request);

    JsonNode getConversation(String principalToken, String conversationId);
}
