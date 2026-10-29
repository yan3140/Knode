package com.yan.knowledge.core.port;

import com.yan.knowledge.core.domain.IndexIdentity;

public interface EmbeddingProvider {

    float[] embed(String text);

    IndexIdentity indexIdentity();
}
