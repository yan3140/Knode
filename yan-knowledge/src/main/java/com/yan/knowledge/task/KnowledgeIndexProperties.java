package com.yan.knowledge.task;

import com.yan.knowledge.core.domain.IndexProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "blog.knowledge.index")
public class KnowledgeIndexProperties {

    private boolean keywordEnabled = true;
    private boolean vectorEnabled = true;
    private String chunkingPolicyVersion = "markdown-heading-v1";
    private String keywordSchemaVersion = "elasticsearch-standard-v1";
    private boolean requireKeyword = true;
    private boolean requireVector;
    private boolean allowPartial = true;

    public IndexProfile indexProfile() {
        return new IndexProfile(keywordEnabled, vectorEnabled);
    }

    public boolean isKeywordEnabled() {
        return keywordEnabled;
    }

    public void setKeywordEnabled(boolean keywordEnabled) {
        this.keywordEnabled = keywordEnabled;
    }

    public boolean isVectorEnabled() {
        return vectorEnabled;
    }

    public void setVectorEnabled(boolean vectorEnabled) {
        this.vectorEnabled = vectorEnabled;
    }

    public String getChunkingPolicyVersion() {
        return chunkingPolicyVersion;
    }

    public void setChunkingPolicyVersion(String chunkingPolicyVersion) {
        this.chunkingPolicyVersion = chunkingPolicyVersion;
    }

    public String getKeywordSchemaVersion() {
        return keywordSchemaVersion;
    }

    public void setKeywordSchemaVersion(String keywordSchemaVersion) {
        this.keywordSchemaVersion = keywordSchemaVersion;
    }

    public boolean isRequireKeyword() {
        return requireKeyword;
    }

    public void setRequireKeyword(boolean requireKeyword) {
        this.requireKeyword = requireKeyword;
    }

    public boolean isRequireVector() {
        return requireVector;
    }

    public void setRequireVector(boolean requireVector) {
        this.requireVector = requireVector;
    }

    public boolean isAllowPartial() {
        return allowPartial;
    }

    public void setAllowPartial(boolean allowPartial) {
        this.allowPartial = allowPartial;
    }
}
