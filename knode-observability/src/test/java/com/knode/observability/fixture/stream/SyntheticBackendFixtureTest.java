package com.knode.observability.fixture.stream;

import com.knode.observability.EventTraceContext;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** Local backend smoke: synthetic event and local H2 only; no Redis or User process. */
@EnabledIfEnvironmentVariable(named="KNODE_FIXTURE_SYNTHETIC_OTLP", matches="http://127\\.0\\.0\\.1:[0-9]+/v1/traces")
class SyntheticBackendFixtureTest {
    @Test void twoAttemptsShareSyntheticProducer() throws Exception {
        String run=System.getenv("KNODE_FIXTURE_SYNTHETIC_RUN");
        if(run==null || !run.matches("[a-f0-9]{12}"))throw new IllegalArgumentException("Missing synthetic run id");
        var exporter=OtlpHttpSpanExporter.builder().setEndpoint(System.getenv("KNODE_FIXTURE_SYNTHETIC_OTLP"))
                .setTimeout(Duration.ofSeconds(2)).build();
        var resource=Resource.getDefault().merge(Resource.create(Attributes.builder()
                .put("service.name","knode-stream-observability-fixture").build()));
        var provider=SdkTracerProvider.builder().setResource(resource).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        var telemetry=OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        Span producer=telemetry.getTracer("fixture").spanBuilder("fixture.synthetic.producer").startSpan();
        String traceId=producer.getSpanContext().getTraceId(), parentId=producer.getSpanContext().getSpanId(), parent;
        try(var scope=producer.makeCurrent()){parent=new EventTraceContext(telemetry).captureCurrent().orElseThrow().traceparent();}
        String jdbc="jdbc:h2:mem:synthetic_backend";
        try(FixtureInbox inbox=new FixtureInbox(jdbc,telemetry)){
            assertThat(inbox.process("1-0",Map.of("event",FixtureInboxTest.event(2,"LOGOUT",parent))).status()).isEqualTo("APPLIED");
            assertThat(inbox.process("1-0",Map.of("event",FixtureInboxTest.event(2,"LOGOUT",parent))).status()).isEqualTo("DUPLICATE");
            assertThat(inbox.count("inbox")).isEqualTo(1);
        }
        producer.end();assertThat(provider.forceFlush().join(5,TimeUnit.SECONDS).isSuccess()).isTrue();provider.close();
        Path output=Path.of("target","stream-synthetic-"+run,"trace.json");
        Files.createDirectory(output.getParent());
        Files.writeString(output,"{\"synthetic\":true,\"traceId\":\""+traceId+"\",\"producerSpanId\":\""+parentId+"\",\"attempts\":2,\"effects\":1}");
    }
}
