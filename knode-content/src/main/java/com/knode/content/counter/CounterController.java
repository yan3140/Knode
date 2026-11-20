package com.knode.content.counter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class CounterController {
    private final CounterAccess access;
    public CounterController(CounterAccess access) { this.access=access; }
    @PutMapping({"/api/blog/article/updateViewCount/{id}","/article/updateViewCount/{id}"})
    public ResponseEntity<Map<String,Object>> hit(@PathVariable String id,HttpServletRequest request) {
        long value;
        try {
            if(!id.matches("[0-9]{1,19}")) throw new NumberFormatException();
            value=Long.parseLong(id); if(value<=0) throw new NumberFormatException();
        } catch(NumberFormatException failure) { throw new IllegalArgumentException("Invalid article id"); }
        List<String> agents=Collections.list(request.getHeaders("User-Agent"));
        if(agents.size()>1) throw new IllegalArgumentException("Invalid user agent");
        CounterStore.Identity identity=CounterIdentity.of(value,request.getRemoteAddr(),agents.isEmpty()?null:agents.get(0));
        int retry=access.hit(value,identity);
        if(retry>0) return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER,Integer.toString(retry))
                .body(Map.of("code",429,"msg","Too many requests"));
        return ResponseEntity.ok(Map.of("code",200,"msg","\u64cd\u4f5c\u6210\u529f"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> invalid() {
        return ResponseEntity.ok(Map.of("code",514,"msg","\u6587\u7ae0\u53c2\u6570\u4e0d\u5408\u6cd5"));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("code",500,"msg","Service unavailable"));
    }
}
