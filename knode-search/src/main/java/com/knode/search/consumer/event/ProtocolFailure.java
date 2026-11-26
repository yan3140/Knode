package com.knode.search.consumer.event;

public record ProtocolFailure(
        String eventId,
        String errorCode,
        String errorMessage,
        String payloadSha256,
        int payloadBytes
) {
    public String failureKey(String streamRecordId) {
        if (eventId != null && !eventId.isBlank()) {
            return "event:" + eventId;
        }
        return "stream:" + streamRecordId;
    }
}
