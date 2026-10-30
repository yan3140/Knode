package com.knode.user.web;

import com.knode.user.auth.SessionStore;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final SessionStore.Reader sessions;
    public HealthController(JdbcTemplate jdbc,SessionStore.Reader sessions) { this.jdbc=jdbc;this.sessions=sessions; }
    @GetMapping("/health/live") public Map<String,String> live() { return Map.of("status","UP"); }
    @GetMapping("/health/ready") public Map<String,String> ready() {
        jdbc.queryForObject("SELECT 1",Integer.class);
        try { sessions.find(UUID.randomUUID().toString()); }
        catch (SessionStore.Unavailable e) { throw new com.knode.user.auth.AuthenticationFailure(503); }
        return Map.of("status","UP");
    }
}
