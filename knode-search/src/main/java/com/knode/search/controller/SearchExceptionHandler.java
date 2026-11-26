package com.knode.search.controller;

import com.knode.search.model.ApiResponse;
import com.knode.search.model.SearchBusinessException;
import com.knode.search.model.SearchErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
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
