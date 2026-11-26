package com.knode.search.rebuild.client;

public class ContentSnapshotException extends RuntimeException {
    private final ErrorType errorType;
    private final int httpStatus;
    private final String errorCode;
    private final Long retryAfterSeconds;

    public ContentSnapshotException(
            ErrorType errorType,
            int httpStatus,
            String errorCode,
            String message,
            Long retryAfterSeconds,
            Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ContentSnapshotException expired410(String errorCode, String message) {
        return new ContentSnapshotException(ErrorType.EXPIRED_410, 410, errorCode, message, null, null);
    }

    public static ContentSnapshotException retryable(
            int httpStatus, String errorCode, String message, Long retryAfterSeconds, Throwable cause) {
        return new ContentSnapshotException(ErrorType.RETRYABLE, httpStatus, errorCode, message, retryAfterSeconds, cause);
    }

    public static ContentSnapshotException nonRetryable(int httpStatus, String errorCode, String message) {
        return new ContentSnapshotException(ErrorType.NON_RETRYABLE, httpStatus, errorCode, message, null, null);
    }

    public ErrorType errorType() {
        return errorType;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isExpired410() {
        return errorType == ErrorType.EXPIRED_410;
    }

    public boolean isRetryable() {
        return errorType == ErrorType.RETRYABLE;
    }

    public enum ErrorType {
        EXPIRED_410,
        RETRYABLE,
        NON_RETRYABLE
    }
}
