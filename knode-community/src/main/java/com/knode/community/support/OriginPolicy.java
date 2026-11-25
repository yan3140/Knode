package com.knode.community.support;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class OriginPolicy {
    public static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(1_048_576)
                    .maxNestingDepth(32)
                    .maxStringLength(65_536)
                    .maxNumberLength(32)
                    .build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private OriginPolicy() {}

    public static URI parseOptional(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid " + label + " origin");
        }
        String host = uri.getHost();
        String path = uri.getRawPath();
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host));
        if ((!secure && !loopback)
                || host == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(path == null || path.isEmpty() || "/".equals(path))
                || uri.getPort() < -1
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Invalid " + label + " origin");
        }
        return uri;
    }

    public static HttpClient newClient() {
        return HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .connectTimeout(Duration.ofMillis(400))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static boolean validPositiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    public static long parsePositiveIdOrZero(String value) {
        if (!validPositiveId(value)) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    public static Long parseNonZeroSignedId(String value) {
        if (value == null || !value.matches("-?[1-9][0-9]{0,18}")) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed == 0L || parsed == Long.MIN_VALUE) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public static boolean exactFields(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject() || node.size() != required.size()) {
            return false;
        }
        for (String name : required) {
            if (!node.has(name)) {
                return false;
            }
        }
        return true;
    }

    public static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public BoundedBody(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if ((long) buffer.size() + chunk.remaining() > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalArgumentException("Response body exceeds limit"));
                    return;
                }
                byte[] bytes = new byte[chunk.remaining()];
                chunk.get(bytes);
                buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable ignored) {
            result.completeExceptionally(new IllegalStateException());
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }
    }
}
