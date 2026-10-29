package com.yan.knowledge.core.port;

import com.yan.knowledge.core.ingest.ParsedDocument;

public interface DocumentParser {

    ParsedDocument parse(String fileName, byte[] content);
}
