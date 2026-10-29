package com.yan.knowledge.core.port;

import com.yan.knowledge.core.domain.IndexAvailabilityPolicy;
import com.yan.knowledge.core.domain.KnowledgeDocumentVersion;

public interface IndexAvailabilityPolicyProvider {

    IndexAvailabilityPolicy policyFor(KnowledgeDocumentVersion version);
}
