package com.knode.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A durable, non-secret recovery record; written before any Redis operation. */
final class ExternalRelayManifest {
    private ExternalRelayManifest() {}
    static Path create(String environment,String stream,String probePrefix) throws IOException {
        if (environment==null || !environment.matches("verify-[a-z0-9-]{1,25}"))
            throw new IllegalArgumentException("A verify- environment is required");
        String allowed="knode:"+environment+":v1:business:";
        if (stream==null || probePrefix==null || !stream.startsWith(allowed) || !probePrefix.startsWith(allowed)
                || !stream.matches("[A-Za-z0-9:_-]{1,255}") || !probePrefix.matches("[A-Za-z0-9:_-]{1,218}")
                || stream.equals(probePrefix) || stream.startsWith(probePrefix+":"))
            throw new IllegalArgumentException("Test keys must be distinct within the verify business prefix");
        Path directory=Path.of("target","external-relay-manifests").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path manifest=directory.resolve("relay-"+UUID.randomUUID()+".json");
        String json=new ObjectMapper().writeValueAsString(Map.of("schemaVersion",1,"environment",environment,
                "streamKey",stream,"probePrefix",probePrefix,"createdAt",Instant.now().toString()))+"\n";
        try (FileChannel file=FileChannel.open(manifest,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            ByteBuffer bytes=StandardCharsets.UTF_8.encode(json);
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
        return manifest;
    }
}
