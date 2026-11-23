package com.knode.content.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import org.springframework.stereotype.Component;

import static com.knode.content.snapshot.SnapshotFailure.Code.*;

@Component
class SnapshotCursor {
    static final int MAX_LENGTH = 512;
    enum Kind { PAGE, CHANGES }
    record Position(SnapshotRequests.Purpose purpose, String snapshotId, long offset, Instant expiresAt) {}

    private final SnapshotSettings settings;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    SnapshotCursor(SnapshotSettings settings) {
        this.settings = settings;
    }

    String issue(Kind kind, String owner, SnapshotRequests.Purpose purpose, String snapshotId, long offset, Instant expires) {
        String fields = String.join("\n", "1", kind.name(), settings.epoch(), owner, purpose.name(),
                snapshotId == null ? "-" : snapshotId, Long.toString(offset), Long.toString(expires.toEpochMilli()));
        byte[] payload = fields.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    Position open(String token, Kind expected, String owner, Instant now) {
        if (token == null || token.length() > MAX_LENGTH || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) throw invalid();
        try {
            int separator = token.indexOf('.');
            String bodyPart = token.substring(0, separator);
            String macPart = token.substring(separator + 1);
            byte[] payload = DECODER.decode(bodyPart);
            byte[] mac = DECODER.decode(macPart);
            if (!bodyPart.equals(ENCODER.encodeToString(payload)) || !macPart.equals(ENCODER.encodeToString(mac))
                    || !MessageDigest.isEqual(sign(payload), mac)) throw invalid();
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 8 || !"1".equals(fields[0]) || !expected.name().equals(fields[1])) throw invalid();
            SnapshotRequests.Purpose purpose = SnapshotRequests.Purpose.valueOf(fields[4]);
            if (!purpose.rebuild() || (expected == Kind.PAGE ? !SnapshotRequests.canonicalUuid(fields[5]) : !"-".equals(fields[5]))) {
                throw invalid();
            }
            if (!owner.equals(fields[3])) throw new SnapshotFailure(SERVICE_PERMISSION_DENIED);
            Instant expires = Instant.ofEpochMilli(SnapshotRequests.decimal(fields[7], false));
            if (!settings.epoch().equals(fields[2]) || !now.isBefore(expires)) {
                throw new SnapshotFailure(expected == Kind.PAGE ? SNAPSHOT_EXPIRED : CURSOR_EXPIRED);
            }
            return new Position(purpose, fields[5], SnapshotRequests.decimal(fields[6], expected == Kind.CHANGES), expires);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(settings.key());
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Snapshot cursor signer unavailable", e);
        }
    }

    private static SnapshotFailure invalid() {
        return new SnapshotFailure(INVALID_ARGUMENT);
    }
}
