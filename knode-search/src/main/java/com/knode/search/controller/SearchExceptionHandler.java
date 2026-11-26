package com.knode.search.controller;

import com.knode.search.model.ApiResponse;
import com.knode.search.model.SearchBusinessException;
import com.knode.search.model.SearchErrorCode;
import com.knode.search.ops.AdminDependencyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class SearchExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchExceptionHandler.class);

    @ExceptionHandler(SearchBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleSearchBusinessException(SearchBusinessException ex) {
        return ResponseEntity.ok(ApiResponse.error(ex.getErrorCode()));
    }

    @ExceptionHandler(AdminDependencyUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAdminDependencyUnavailable(AdminDependencyUnavailableException ex) {
        LOGGER.warn("event=search_admin_dependency_unavailable reason={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", "no-store")
                .body(ApiResponse.error(SearchErrorCode.SEARCH_SERVICE_UNAVAILABLE));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header("Cache-Control", "no-store")
                .body(new ApiResponse<>(405, "不支持的请求方法", null));
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            BindException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleParamException(Exception ex) {
        return ResponseEntity.ok(ApiResponse.error(SearchErrorCode.PARAM_ERROR));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        LOGGER.warn("event=search_unexpected_exception errorType={}", ex.getClass().getSimpleName());
        return ResponseEntity.ok(ApiResponse.error(SearchErrorCode.SEARCH_SERVICE_UNAVAILABLE));
    }
}
