package com.knode.content.snapshot;

import java.time.Duration;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.knode.content.snapshot.SnapshotFailure.Code.FEATURE_DISABLED;

@Component
public class SnapshotSettings {
    static final int MAX_ITEMS = 100;
    static final int MAX_ITEM_BYTES = 8_000_000;
    static final int EXPORT_WORK_SIZE = 20;
    static final int MAX_EXPORT_ITEMS = 20_000;
    static final long MAX_EXPORT_BYTES = 128_000_000;
    static final int MAX_STORED_EXPORTS = 4;
    static final int MAX_REQUEST_RECORDS = 256;
    static final Duration SNAPSHOT_TTL = Duration.ofMinutes(30);
    static final Duration FEED_TTL = Duration.ofDays(7);
    static final Duration REQUEST_TOMBSTONE_TTL = Duration.ofHours(24);

    private final boolean enabled;
    private final boolean cleanup;
    private final String epoch;
    private final SecretKeySpec signingKey;

    public SnapshotSettings(@Value("${KNODE_CONTENT_SNAPSHOT_API_ENABLED:false}") boolean enabled,
                            @Value("${KNODE_CONTENT_SNAPSHOT_CLEANUP_ENABLED:true}") boolean cleanup,
                            @Value("${KNODE_CONTENT_SNAPSHOT_EPOCH:}") String epoch,
                            @Value("${KNODE_CONTENT_SNAPSHOT_CURSOR_KEY:}") String key) {
        this.enabled = enabled;
        this.cleanup = enabled && cleanup;
        this.epoch = epoch;
        this.signingKey = enabled ? signingKey(epoch, key) : null;
    }

    private static SecretKeySpec signingKey(String epoch, String key) {
        if (!SnapshotRequests.canonicalUuid(epoch) || !key.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Snapshot API requires a UUID epoch and a base64url 32-byte cursor key");
        }
        byte[] bytes = Base64.getUrlDecoder().decode(key);
        if (bytes.length != 32 || !key.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))) {
            throw new IllegalArgumentException("Snapshot API requires a canonical 32-byte cursor key");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    void requireEnabled() {
        if (!enabled) throw new SnapshotFailure(FEATURE_DISABLED);
    }

    boolean cleanupEnabled() {
        return cleanup;
    }

    String epoch() {
        return epoch;
    }

    SecretKeySpec key() {
        return signingKey;
    }
}
