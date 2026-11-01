package com.knode.user.internal;

import com.knode.auth.AuthFailure;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={InternalController.class,DelegationController.class})
public class InternalErrors {
    private ResponseEntity<Map<String,Object>> result(int status,String code) {
        return ResponseEntity.status(status).body(Map.of("error",Map.of("code",code,"message",status==503?"Dependency unavailable":"Request rejected","retryable",status==503)));
    }
    @ExceptionHandler(InternalFailure.class)
    public ResponseEntity<Map<String,Object>> failure(InternalFailure e) {return result(e.status(),e.code());}
    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String,Object>> auth(AuthFailure e) {return result(e.status(),e.status()==403?"SERVICE_PERMISSION_DENIED":"INVALID_SERVICE_TOKEN");}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String,Object>> database(DataAccessException e) {return result(503,"DEPENDENCY_UNAVAILABLE");}
}
