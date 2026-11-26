package com.knode.search.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(Integer code, String msg, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SearchErrorCode.SUCCESS.getCode(), SearchErrorCode.SUCCESS.getMsg(), data);
    }

    public static ApiResponse<Void> error(SearchErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMsg(), null);
    }
}
