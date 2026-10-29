package com.yan.knowledge.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration reserved for the conversational Agent and kept separate from embeddings. */
@Component
@ConfigurationProperties(prefix = "blog.ai.agent")
public class AgentModelProperties {

    private String apiKey = "";
    private String responsesUrl = "https://api.wenwenai.org/v1/responses";
    private String model = "gpt-5.6";
    private String reasoningEffort = "medium";
    private boolean stream;
    private boolean store;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getResponsesUrl() {
        return responsesUrl;
    }

    public void setResponsesUrl(String responsesUrl) {
        this.responsesUrl = responsesUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public boolean isStore() {
        return store;
    }

    public void setStore(boolean store) {
        this.store = store;
    }
}
