package com.knode.observability.fixture.stream;

import com.knode.observability.EventTraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class FixtureInboxTest {
    @TempDir Path temp;
    static final String ID="11111111-1111-1111-1111-111111111111";
    static final String SID="22222222-2222-2222-2222-222222222222";
    static String event(int version,String reason,String trace) {
        return "{\"eventId\":\""+ID+"\",\"eventType\":\"SessionRevoked\",\"schemaVersion\":"+version+
                ",\"producer\":\"knode-user\",\"aggregateType\":\"AUTH_SESSION\",\"aggregateId\":\""+SID+
                "\",\"sourceRevision\":\"1\",\"occurredAt\":\"2026-11-01T00:00:00Z\",\"visibility\":\"INTERNAL\","+
                "\"payload\":{\"sid\":\""+SID+"\",\"userId\":\"42\",\"scope\":\"blog\",\"reason\":\""+reason+
                "\",\"executionScope\":\"SID_CURRENT_EXECUTIONS\"}"+(trace==null?"":",\"traceContext\":{\"traceparent\":\""+trace+"\"}")+"}";
    }
    @Test void persistedEffectSurvivesRestartAndRetryUsesStoredProducerNotAmbient() throws Exception {
        var exporter=InMemorySpanExporter.create();
        var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        var telemetry=OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        Span producer=telemetry.getTracer("test").spanBuilder("producer").startSpan();
        String parent;
        try(var scope=producer.makeCurrent()) {parent=new EventTraceContext(telemetry).captureCurrent().orElseThrow().traceparent();}
        String jdbc="jdbc:h2:file:"+temp.resolve("inbox").toString().replace('\\','/');
        try(FixtureInbox inbox=new FixtureInbox(jdbc,telemetry)) {
            assertThat(inbox.process("1-0",Map.of("event",event(2,"LOGOUT",parent))).status()).isEqualTo("APPLIED");
            assertThat(inbox.count("inbox")).isEqualTo(1);
        }
        Span ambient=telemetry.getTracer("test").spanBuilder("ambient").startSpan();
        try(var scope=ambient.makeCurrent();FixtureInbox restarted=new FixtureInbox(jdbc,telemetry)) {
            var result=restarted.process("1-0",Map.of("event",event(2,"LOGOUT",parent)));
            assertThat(result.status()).isEqualTo("DUPLICATE");
            assertThat(result.attempt()).isEqualTo(2);
            assertThat(restarted.count("inbox")).isEqualTo(1);
            assertThat(restarted.process("2-0",Map.of("event",event(2,"ACCOUNT_SECURITY_CHANGED",parent))).status()).isEqualTo("CONFLICT");
            assertThat(restarted.count("conflicts")).isEqualTo(1);
            assertThat(restarted.process("3-0",Map.of("event",event(3,"LOGOUT",null))).acknowledge()).isFalse();
            assertThat(restarted.process("4-0",Map.of("event",event(1,"LOGOUT",parent))).status()).isEqualTo("UNSUPPORTED");
            assertThat(restarted.process("5-0",Map.of("event",event(1,"LOGOUT",null))).status()).isEqualTo("CONFLICT");
            assertThat(restarted.count("inbox")).isEqualTo(1);
        }
        ambient.end();producer.end();
        var spans=exporter.getFinishedSpanItems().stream().filter(s->s.getName().equals("fixture.session_revoked.consume")).toList();
        assertThat(spans).hasSize(6);
        assertThat(spans.get(0).getParentSpanId()).isEqualTo(producer.getSpanContext().getSpanId());
        assertThat(spans.get(1).getParentSpanId()).isEqualTo(producer.getSpanContext().getSpanId());
        assertThat(spans.get(1).getTraceId()).isEqualTo(producer.getSpanContext().getTraceId());
        assertThat(spans.get(1).getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("knode.event.delivery_attempt"))).isEqualTo(2);
        assertThat(spans.get(3).getParentSpanId()).isEqualTo("0000000000000000");
        provider.close();
    }
    @Test void malformedContextDoesNotAffectValidV2AndV1Works() throws Exception {
        try(FixtureInbox db=new FixtureInbox("jdbc:h2:mem:"+UUID.randomUUID(),OpenTelemetrySdk.builder().build())) {
            assertThat(db.process("1-0",Map.of("event",event(1,"LOGOUT",null))).status()).isEqualTo("APPLIED");
            String v2=event(2,"LOGOUT","invalid").replace(ID,"33333333-3333-3333-3333-333333333333");
            assertThat(db.process("2-0",Map.of("event",v2)).status()).isEqualTo("APPLIED");
            assertThat(db.process("3-0",Map.of("event",v2,"unexpected","private")).acknowledge()).isFalse();
        }
    }
    @Test void connectionGuardRequiresMatchingRunNamespaceAndDedicatedCredentials() throws Exception {
        Map<String,String> e=new HashMap<>(Map.ofEntries(
                Map.entry("KNODE_FIXTURE_ENVIRONMENT","verify-local"),Map.entry("KNODE_FIXTURE_RUN","abcdef123456"),
                Map.entry("KNODE_FIXTURE_STREAM","knode:verify-local:v1:business:stream:abcdef123456"),
                Map.entry("KNODE_FIXTURE_GROUP","fixture-abcdef123456"),Map.entry("KNODE_FIXTURE_CONSUMER","fixture-abcdef123456-one"),
                Map.entry("KNODE_FIXTURE_REDIS_HOST","127.0.0.1"),Map.entry("KNODE_FIXTURE_REDIS_PORT","16379"),
                Map.entry("KNODE_FIXTURE_REDIS_DATABASE","0"),Map.entry("KNODE_FIXTURE_REDIS_USERNAME","knode_verify_fixture"),
                Map.entry("KNODE_FIXTURE_REDIS_PASSWORD","synthetic-only-123456"),Map.entry("KNODE_FIXTURE_REDIS_TLS","true"),
                Map.entry("KNODE_FIXTURE_MODE","create"),Map.entry("KNODE_FIXTURE_OTLP_HTTP","http://127.0.0.1:4318/v1/traces")));
        assertThat(StreamFixture.Config.from(e).group()).isEqualTo("fixture-abcdef123456");
        for(var change:Map.of("KNODE_FIXTURE_ENVIRONMENT","production","KNODE_FIXTURE_STREAM","blog:old",
                "KNODE_FIXTURE_GROUP","other","KNODE_FIXTURE_REDIS_USERNAME","default",
                "KNODE_FIXTURE_OTLP_HTTP","https://example.com/v1/traces").entrySet()) {
            var bad=new HashMap<>(e);bad.put(change.getKey(),change.getValue());
            assertThatThrownBy(()->StreamFixture.Config.from(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsSecondJsonRootWithoutEffectOrAck() throws Exception {
        assertUnsupportedAcrossRestart(event(1,"LOGOUT",null)+" {}");
    }
    @Test void rejectsTrailingGarbageWithoutEffectOrAck() throws Exception {
        assertUnsupportedAcrossRestart(event(1,"LOGOUT",null)+" garbage");
    }
    @Test void rejectsDuplicateFieldWithoutEffectOrAck() throws Exception {
        assertUnsupportedAcrossRestart(event(1,"LOGOUT",null).replace(
                "\"eventType\":\"SessionRevoked\"",
                "\"eventType\":\"SessionRevoked\",\"eventType\":\"SessionRevoked\""));
    }
    @Test void rejectsUserIdJustAboveInt64Maximum() throws Exception {
        assertUnsupportedAcrossRestart(event(1,"LOGOUT",null).replace(
                "\"userId\":\"42\"","\"userId\":\"9223372036854775808\""));
    }
    @Test void rejectsNineteenDigitUserIdAboveInt64Maximum() throws Exception {
        assertUnsupportedAcrossRestart(event(1,"LOGOUT",null).replace(
                "\"userId\":\"42\"","\"userId\":\"9999999999999999999\""));
    }
    @Test void acceptsInt64MaximumAndTrailingWhitespace() throws Exception {
        String jdbc="jdbc:h2:file:"+temp.resolve("maximum").toString().replace('\\','/');
        String valid=event(1,"LOGOUT",null).replace("\"userId\":\"42\"",
                "\"userId\":\"9223372036854775807\"")+" \n\t\r";
        try(FixtureInbox inbox=new FixtureInbox(jdbc,OpenTelemetrySdk.builder().build())) {
            var result=inbox.process("1-0",Map.of("event",valid));
            assertThat(result.status()).isEqualTo("APPLIED");
            assertThat(result.acknowledge()).isTrue();
            assertThat(inbox.count("inbox")).isEqualTo(1);
        }
    }
    private void assertUnsupportedAcrossRestart(String value) throws Exception {
        String jdbc="jdbc:h2:file:"+temp.resolve("invalid").toString().replace('\\','/');
        try(FixtureInbox inbox=new FixtureInbox(jdbc,OpenTelemetrySdk.builder().build())) {
            var result=inbox.process("1-0",Map.of("event",value));
            assertThat(result.status()).isEqualTo("UNSUPPORTED");
            assertThat(result.acknowledge()).isFalse();
            assertThat(inbox.count("inbox")).isZero();
            assertThat(inbox.count("delivery")).isEqualTo(1);
        }
        try(FixtureInbox restarted=new FixtureInbox(jdbc,OpenTelemetrySdk.builder().build())) {
            try(var connection=java.sql.DriverManager.getConnection(jdbc);
                var statement=connection.createStatement();
                var rows=statement.executeQuery("SELECT status,attempts FROM delivery")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo("UNSUPPORTED");
                assertThat(rows.getInt("attempts")).isEqualTo(1);
                assertThat(rows.next()).isFalse();
            }
            var retry=restarted.process("1-0",Map.of("event",value));
            assertThat(retry.status()).isEqualTo("UNSUPPORTED");
            assertThat(retry.acknowledge()).isFalse();
            assertThat(retry.attempt()).isEqualTo(2);
            assertThat(restarted.count("inbox")).isZero();
            assertThat(restarted.count("delivery")).isEqualTo(1);
        }
    }

}
