package com.knode.search.ops;

public class InternalOpsException extends RuntimeException {
    private final int httpStatus;
    private final String errorCode;
    private final boolean retryable;

    public InternalOpsException(int httpStatus, String errorCode, String message, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public InternalOpsException(int httpStatus, String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
