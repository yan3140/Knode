package com.yan.knowledge.core.port;

import com.yan.knowledge.core.retrieval.ExactSignal;

import java.util.List;

public interface ExactSignalExtractor {

    List<ExactSignal> extract(String query);
}
