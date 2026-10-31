package com.knode.governance.fixture;

import com.knode.governance.GovernedCall;
import feign.Feign;
import feign.Request;
import feign.RequestLine;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(GovernanceFixture.Endpoint.class)
public class GovernanceFixture {
    public static void main(String[] args) { SpringApplication.run(GovernanceFixture.class, args); }
    interface Dependency { @RequestLine("GET /fixture/dependency") String fetch(); }
    @RestController
    static class Endpoint {
        final AtomicBoolean failure = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        final GovernedCall governed;
        Endpoint(GovernedCall governed) { this.governed = governed; }
        @GetMapping("/fixture/probe/{id}") String probe(@PathVariable String id) { return "fixture-ok"; }
        @PostMapping("/fixture/failure") void failure(@RequestParam boolean enabled) { failure.set(enabled); }
        @GetMapping("/fixture/count") int count() { return calls.get(); }
        @GetMapping("/fixture/dependency") String dependency() {
            calls.incrementAndGet();
            if (failure.get()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            return "dependency-ok";
        }
        @GetMapping("/fixture/call") String call(HttpServletRequest request) {
            Dependency d = Feign.builder().options(new Request.Options(1, TimeUnit.SECONDS, 1, TimeUnit.SECONDS, true))
                .target(Dependency.class, "http://127.0.0.1:" + request.getLocalPort());
            return governed.execute("feign:fixture#fetch", d::fetch);
        }
    }
}
