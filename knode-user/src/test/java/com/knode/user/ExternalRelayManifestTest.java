package com.knode.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalRelayManifestTest {
    @Test void manifestSurvivesFailureAndContainsOnlyScopedNonSecretRecoveryFields() throws Exception {
        String environment="verify-test",prefix="knode:verify-test:v1:business:",stream=prefix+"relay-"+UUID.randomUUID(),probe=prefix+"probe-"+UUID.randomUUID();
        Path manifest=ExternalRelayManifest.create(environment,stream,probe);
        try {
            assertTrue(manifest.startsWith(Path.of("target","external-relay-manifests").toAbsolutePath().normalize()));
            assertThrows(IllegalStateException.class,() -> {throw new IllegalStateException("simulated abrupt test failure");});
            var json=new ObjectMapper().readTree(Files.readString(manifest));
            Set<String> fields=new HashSet<>();json.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("schemaVersion","environment","streamKey","probePrefix","createdAt"),fields);
            assertEquals(1,json.path("schemaVersion").asInt());assertEquals(environment,json.path("environment").asText());
            assertEquals(stream,json.path("streamKey").asText());assertEquals(probe,json.path("probePrefix").asText());
            java.time.Instant.parse(json.path("createdAt").asText());
        } finally {Files.deleteIfExists(manifest);}
    }
    /** Deliberately bypasses shutdown hooks, simulating a killed test JVM without touching Redis. */
    public static class AbruptExitFixture {
        public static void main(String[] args) throws Exception {
            Path manifest=ExternalRelayManifest.create(args[0],args[1],args[2]);
            System.out.println(manifest);System.out.flush();
            Runtime.getRuntime().halt(17);
        }
    }

    @Test void forcedJvmExitLeavesRecoveryManifest() throws Exception {
        String environment="verify-test",prefix="knode:verify-test:v1:business:";
        String stream=prefix+"relay-"+UUID.randomUUID(),probe=prefix+"probe-"+UUID.randomUUID();
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
        var process=new ProcessBuilder(executable,"-cp",System.getProperty("java.class.path"),AbruptExitFixture.class.getName(),environment,stream,probe)
                .redirectErrorStream(true).start();
        Path manifest=null;
        try {
            assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS),"fixture did not terminate");
            assertEquals(17,process.exitValue(),"fixture did not halt at the intended point");
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
            manifest=Path.of(output);
            assertTrue(manifest.startsWith(Path.of("target","external-relay-manifests").toAbsolutePath().normalize()));
            var json=new ObjectMapper().readTree(Files.readString(manifest));
            assertEquals(environment,json.path("environment").asText());
            assertEquals(stream,json.path("streamKey").asText());assertEquals(probe,json.path("probePrefix").asText());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            if (manifest!=null) Files.deleteIfExists(manifest);
        }
    }

    @Test void rejectsForeignOrOverlappingKeysBeforeWriting() {
        String environment="verify-test",prefix="knode:verify-test:v1:business:";
        assertThrows(IllegalArgumentException.class,() -> ExternalRelayManifest.create("prod",prefix+"s",prefix+"p"));
        assertThrows(IllegalArgumentException.class,() -> ExternalRelayManifest.create(environment,"knode:other:v1:business:s",prefix+"p"));
        assertThrows(IllegalArgumentException.class,() -> ExternalRelayManifest.create(environment,prefix+"same",prefix+"same"));
        assertThrows(IllegalArgumentException.class,() -> ExternalRelayManifest.create(environment,prefix+"probe:stream",prefix+"probe"));
    }
}
