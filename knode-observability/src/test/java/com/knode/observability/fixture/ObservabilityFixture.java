package com.knode.observability.fixture;

import com.knode.observability.EventTraceContext;
import io.opentelemetry.api.trace.Span;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(ObservabilityFixture.Endpoints.class)
public class ObservabilityFixture {
    public static void main(String[] args) { SpringApplication.run(ObservabilityFixture.class, args); }

    @RestController
    static class Endpoints {
        private final RestClient client;
        private final EventTraceContext events;
        Endpoints(RestClient.Builder builder, EventTraceContext events,
                @Value("${fixture.python-url}") String python) {
            this.client = builder.baseUrl(python).build(); this.events = events;
        }
        @GetMapping("/api/blog/article/obs/{id}")
        String call() {
            return client.get().uri("/fixture/python").retrieve().body(String.class);
        }
        @GetMapping("/api/blog/article/obs-error")
        String fail() { throw new IllegalStateException("fixture error"); }
        @GetMapping("/api/blog/article/obs-event")
        String event() {
            var saved = events.captureCurrent().orElseThrow();
            Thread thread = new Thread(() -> {
                Span consumer = events.startConsumer(saved, "fixture.consume", 2);
                consumer.end();
            }, "fixture-queue-consumer");
            thread.start();
            try { thread.join(2000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return "queued";
        }
    }
}
