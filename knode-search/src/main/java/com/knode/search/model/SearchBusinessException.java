package com.knode.search.model;

public class SearchBusinessException extends RuntimeException {
    private final SearchErrorCode errorCode;

    public SearchBusinessException(SearchErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }

    public SearchBusinessException(SearchErrorCode errorCode, Throwable cause) {
        super(errorCode.getMsg(), cause);
        this.errorCode = errorCode;
    }

    public SearchErrorCode getErrorCode() {
        return errorCode;
    }
}
