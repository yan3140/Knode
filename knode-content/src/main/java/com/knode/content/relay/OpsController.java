package com.knode.content.relay;

import com.knode.auth.ServiceTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class OpsController {
    private final JdbcTemplate db;
    private final ReplayService replay;
    public OpsController(JdbcTemplate db,ReplayService replay) { this.db=db;this.replay=replay; }
    @GetMapping("/internal/v1/ops/outbox/summary")
    public Map<String,Object> summary(HttpServletRequest request) {
        require(request,"ops:read");
        Map<String,Object> states=new LinkedHashMap<>();
        for(String state:List.of("PENDING","LEASED","SENT","DEAD","LOCAL_ONLY")) states.put(state,"0");
        db.query("SELECT state,COUNT(*) FROM content_event_delivery GROUP BY state LIMIT 6",rs -> {
            String state=rs.getString(1);
            if(!states.containsKey(state)) throw new IllegalStateException("Unexpected delivery state");
            states.put(state,Long.toString(rs.getLong(2)));
        });
        List<Map<String,Object>> progress=db.query("SELECT family,cursor_event_id,completed_passes,failure_count,last_error FROM content_event_backfill ORDER BY family LIMIT 4",
                (r,n) -> {
                    Map<String,Object> item=new LinkedHashMap<>();
                    item.put("family",r.getString(1));item.put("cursor",r.getString(2));item.put("completedPasses",Long.toString(r.getLong(3)));
                    item.put("failureCount",Long.toString(r.getLong(4)));item.put("lastError",r.getString(5));return item;
                });
        if(progress.size()!=3) throw new IllegalStateException("Missing backfill state");
        return Map.of("code",200,"msg","OK","data",Map.of("states",states,"backfill",progress,
                "note","SENT indicates stream append, not consumer completion"));
    }
    @PostMapping("/internal/v1/ops/outbox/{eventId}/redeliver")
    public Map<String,Object> redeliver(@PathVariable String eventId,@RequestBody ReplayInput body,HttpServletRequest request) {
        ServiceTokenVerifier.Principal principal=require(request,"ops:redeliver");
        try { if(!UUID.fromString(eventId).toString().equals(eventId)) throw new IllegalArgumentException(); }
        catch(Exception failure) { throw new OpsFailure(400); }
        if(body==null) throw new OpsFailure(400);
        String requestId=body.replayRequestId();
        replay.apply(eventId,requestId,principal.service());
        return Map.of("code",200,"msg","Accepted","data",Map.of("eventId",eventId,"replayRequestId",requestId));
    }
    private static ServiceTokenVerifier.Principal require(HttpServletRequest request,String permission) {
        Object value=request.getAttribute(MachineAuth.PRINCIPAL);
        if(!(value instanceof ServiceTokenVerifier.Principal principal)) throw new OpsFailure(401);
        if(!"knode-content".equals(principal.audience()) || principal.permissions()==null || !principal.permissions().contains(permission))
            throw new OpsFailure(403);
        return principal;
    }
    @ExceptionHandler(OpsFailure.class)
    public ResponseEntity<Map<String,Object>> failure(OpsFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code",failure.status()==503?500:failure.status(),"msg","Request failed"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformed() { return ResponseEntity.badRequest().body(Map.of("code",400,"msg","Invalid request")); }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> unavailable() { return ResponseEntity.status(503).body(Map.of("code",500,"msg","Service unavailable")); }
}
