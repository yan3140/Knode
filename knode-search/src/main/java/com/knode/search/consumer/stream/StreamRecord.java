package com.knode.search.consumer.stream;

import java.util.Map;

public record StreamRecord(String recordId, Map<String, String> fields) {
}
