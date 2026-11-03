package com.knode.user.web;

import com.knode.user.account.BusinessFailure;
import com.knode.user.auth.AuthenticationFailure;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(com.knode.user.account.LoginAttemptLimiter.Limited.class)
    public ResponseEntity<ApiResponse> loginLimited(com.knode.user.account.LoginAttemptLimiter.Limited e) {
        return ResponseEntity.status(429).header("Retry-After",Long.toString(e.retryAfter()))
                .body(new ApiResponse(429,"登录尝试过多，请稍后重试",null));
    }
    @ExceptionHandler(com.knode.user.account.LoginClientAddress.InvalidAddress.class)
    public ResponseEntity<ApiResponse> clientAddress() {
        return ResponseEntity.badRequest().body(new ApiResponse(400,"客户端地址无效",null));
    }
    @ExceptionHandler(BusinessFailure.class)
    public ApiResponse business(BusinessFailure e) { return new ApiResponse(e.code(),e.getMessage(),null); }
    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ApiResponse> auth(AuthenticationFailure e) {
        int status=e.status();
        return ResponseEntity.status(status).body(new ApiResponse(status==503?500:status,
                status==503?"认证服务暂不可用":status==403?"无权限操作":"需要登录后操作",null));
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ApiResponse duplicate(org.springframework.dao.DataIntegrityViolationException e) {
        return new ApiResponse(515,"参数冲突",null);
    }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<ApiResponse> database(RuntimeException e) {
        return ResponseEntity.status(503).body(new ApiResponse(500,"用户服务暂不可用",null));
    }
}
