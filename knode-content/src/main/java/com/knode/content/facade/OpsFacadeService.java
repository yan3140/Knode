package com.knode.content.facade;

import com.knode.content.relay.OpsFailure;
import com.knode.content.relay.ReplayService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OpsFacadeService {
    private static final Set<String> VALID_EVENT_STATUS = Set.of("PENDING", "PROCESSING", "SUCCEEDED", "FAILED", "DEAD");

    private final JdbcTemplate db;
    private final ReplayService replay;
    private final PeerOpsClient peers;

    public OpsFacadeService(JdbcTemplate db, ReplayService replay, PeerOpsClient peers) {
        this.db = db;
        this.replay = replay;
        this.peers = peers;
    }

    public Map<String, Object> summary() {
        Map<String, Object> domains = new LinkedHashMap<>();
        boolean localOk = false;
        boolean anyAvailable = false;
        boolean anyDegraded = false;
        boolean anyUnconfigured = false;

        Map<String, Object> producer = readLocalProducerSummary();
        domains.put("contentProducer", producer);
        if ("AVAILABLE".equals(producer.get("status"))) {
            localOk = true;
            anyAvailable = true;
        } else {
            anyDegraded = true;
        }

        Map<String, Object> metadata = readLocalMetadataSummary();
        domains.put("contentMetadata", metadata);
        if ("AVAILABLE".equals(metadata.get("status"))) {
            localOk = true;
            anyAvailable = true;
        } else {
            anyDegraded = true;
        }

        for (PeerOpsClient.Peer peer : PeerOpsClient.Peer.values()) {
            PeerOpsClient.Outcome outcome = peers.summary(peer);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", outcome.status());
            if ("AVAILABLE".equals(outcome.status())) {
                anyAvailable = true;
                entry.put("summary", outcome.payload());
            } else if ("UNCONFIGURED".equals(outcome.status())) {
                anyUnconfigured = true;
                entry.put("reason", outcome.reason());
            } else {
                anyDegraded = true;
                entry.put("reason", outcome.reason());
            }
            domains.put(peer.domainKey(), entry);
        }

        if (!localOk && !anyAvailable) {
            throw new FacadeFailure(503, 500, "\u8fd0\u7ef4\u6c47\u603b\u670d\u52a1\u6682\u4e0d\u53ef\u7528");
        }

        String overallStatus = anyDegraded ? "DEGRADED" : (anyUnconfigured ? "PARTIAL" : "OK");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overallStatus", overallStatus);
        out.put("atomic", false);
        out.put("domains", domains);
        return out;
    }

    public Map<String, Object> peerEvents(PeerOpsClient.Peer peer, String pageText, String sizeText, String statusText) {
        int pageNum = parsePositiveInt(pageText, 1);
        int pageSize = parsePositiveInt(sizeText, 20);
        if (pageNum < 1 || pageSize < 1 || pageSize > 100 || ((long) pageNum - 1) * pageSize > 10_000_000L) {
            throw new FacadeFailure(400, 400, "Invalid pagination");
        }
        String status = null;
        if (statusText != null && !statusText.isEmpty()) {
            if (!VALID_EVENT_STATUS.contains(statusText)) {
                throw new FacadeFailure(400, 400, "Invalid status filter");
            }
            status = statusText;
        }
        PeerOpsClient.Outcome outcome = peers.events(peer, pageNum, pageSize, status);
        if ("AVAILABLE".equals(outcome.status())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("domain", peer.domainKey());
            out.put("status", "AVAILABLE");
            out.put("rows", outcome.payload().get("rows"));
            out.put("total", outcome.payload().get("total"));
            return out;
        }
        if ("UNCONFIGURED".equals(outcome.status())) {
            throw new FacadeFailure(501, 500, "Consumer domain " + peer.domainKey() + " is not configured");
        }
        if ("INVALID_REQUEST".equals(outcome.status())) {
            throw new FacadeFailure(400, 400, "Invalid request");
        }
        throw new FacadeFailure(503, 500, "Consumer domain " + peer.domainKey() + " is unavailable");
    }

    public Map<String, Object> retryPeer(PeerOpsClient.Peer peer, String replayRequestId, String eventId, String adminUserId) {
        String requestId = resolveReplayRequestId(replayRequestId);
        if (eventId != null) validateUuid(eventId);
        String operator = "admin:" + adminUserId;
        PeerOpsClient.Outcome outcome = peers.retry(peer, requestId, operator, eventId);
        if ("ACCEPTED".equals(outcome.status())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("domain", peer.domainKey());
            out.put("status", "ACCEPTED");
            out.put("replayRequestId", requestId);
            out.put("result", outcome.payload());
            return out;
        }
        if ("UNCONFIGURED".equals(outcome.status())) {
            throw new FacadeFailure(501, 500, "Consumer domain " + peer.domainKey() + " is not configured");
        }
        if ("CONFLICT".equals(outcome.status())) {
            throw new FacadeFailure(409, 409, "Replay conflict");
        }
        if ("NOT_FOUND".equals(outcome.status())) {
            throw new FacadeFailure(404, 404, "Event not found");
        }
        if ("INVALID_REQUEST".equals(outcome.status())) {
            throw new FacadeFailure(400, 400, "Invalid request");
        }
        throw new FacadeFailure(503, 500, "Consumer domain " + peer.domainKey() + " is unavailable");
    }

    public Map<String, Object> redeliverContentEvent(String eventId, String replayRequestId, String adminUserId) {
        validateUuid(eventId);
        if (!OriginPolicy.validReplayRequestId(replayRequestId)) {
            throw new FacadeFailure(400, 400, "Invalid replayRequestId");
        }
        String operator = "admin:" + adminUserId;
        try {
            replay.apply(eventId, replayRequestId, operator);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("domain", "contentProducer");
            out.put("status", "ACCEPTED");
            out.put("eventId", eventId);
            out.put("replayRequestId", replayRequestId);
            out.put("note", "Scheduled for asynchronous stream redelivery; not downstream completion");
            return out;
        } catch (OpsFailure failure) {
            throw new FacadeFailure(failure.status(), failure.status() == 503 ? 500 : failure.status(), "Redeliver failed");
        } catch (RuntimeException failure) {
            throw new FacadeFailure(503, 500, "Content producer ops unavailable");
        }
    }

    public record MultiDomainRetryResult(int httpStatus, int businessCode, Map<String, Object> data) {}

    public MultiDomainRetryResult retryAll(String replayRequestId, List<String> contentEventIds, String adminUserId) {
        String requestId = resolveReplayRequestId(replayRequestId);
        String operator = "admin:" + adminUserId;
        Map<String, Object> domains = new LinkedHashMap<>();
        int attempted = 0;
        int accepted = 0;
        int failed = 0;
        int skipped = 0;

        if (contentEventIds == null || contentEventIds.isEmpty()) {
            skipped++;
            Map<String, Object> producer = new LinkedHashMap<>();
            producer.put("status", "UNSUPPORTED");
            producer.put("reason", "Bulk producer redeliver is disabled; specify contentEventIds or call /content/{eventId}/redeliver");
            domains.put("contentProducer", producer);
        } else {
            List<Map<String, Object>> eventResults = new ArrayList<>(contentEventIds.size());
            int localAccepted = 0;
            int localFailed = 0;
            for (String eventId : contentEventIds) {
                attempted++;
                String scopedRequestId = deriveBatchReplayId(requestId, eventId);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("eventId", eventId);
                item.put("replayRequestId", scopedRequestId);
                try {
                    replay.apply(eventId, scopedRequestId, operator);
                    item.put("status", "ACCEPTED");
                    localAccepted++;
                    accepted++;
                } catch (OpsFailure failure) {
                    String status = switch (failure.status()) {
                        case 404 -> "NOT_FOUND";
                        case 409 -> "CONFLICT";
                        default -> "UNAVAILABLE";
                    };
                    item.put("status", status);
                    localFailed++;
                    failed++;
                } catch (RuntimeException failure) {
                    item.put("status", "UNAVAILABLE");
                    localFailed++;
                    failed++;
                }
                eventResults.add(item);
            }
            Map<String, Object> producer = new LinkedHashMap<>();
            producer.put("status", localFailed == 0 ? "ACCEPTED" : (localAccepted > 0 ? "PARTIAL" : "FAILED"));
            producer.put("events", eventResults);
            domains.put("contentProducer", producer);
        }

        for (PeerOpsClient.Peer peer : PeerOpsClient.Peer.values()) {
            PeerOpsClient.Outcome outcome = peers.retry(peer, requestId, operator, null);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", outcome.status());
            if ("ACCEPTED".equals(outcome.status())) {
                attempted++;
                accepted++;
                entry.put("result", outcome.payload());
            } else if ("UNCONFIGURED".equals(outcome.status())) {
                skipped++;
                entry.put("reason", outcome.reason());
            } else {
                attempted++;
                failed++;
                entry.put("reason", outcome.reason());
            }
            domains.put(peer.domainKey(), entry);
        }

        String overallStatus;
        int httpStatus;
        int businessCode;
        if (attempted > 0 && accepted == 0) {
            overallStatus = "FAILED";
            httpStatus = 503;
            businessCode = 500;
        } else if (attempted > 0 && failed == 0 && skipped == 0) {
            overallStatus = "COMPLETED";
            httpStatus = 200;
            businessCode = 200;
        } else {
            overallStatus = "PARTIAL";
            httpStatus = 200;
            businessCode = 200;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("replayRequestId", requestId);
        data.put("atomic", false);
        data.put("overallStatus", overallStatus);
        data.put("domains", domains);
        return new MultiDomainRetryResult(httpStatus, businessCode, data);
    }

    private Map<String, Object> readLocalProducerSummary() {
        try {
            Map<String, Object> states = new LinkedHashMap<>();
            for (String state : List.of("PENDING", "LEASED", "SENT", "DEAD", "LOCAL_ONLY")) states.put(state, "0");
            db.query("SELECT state,COUNT(*) FROM content_event_delivery GROUP BY state LIMIT 6", rs -> {
                String state = rs.getString(1);
                if (!states.containsKey(state)) throw new IllegalStateException("Unexpected delivery state");
                states.put(state, Long.toString(rs.getLong(2)));
            });
            List<Map<String, Object>> backfill = db.query(
                    "SELECT family,cursor_event_id,completed_passes,failure_count,last_error FROM content_event_backfill ORDER BY family LIMIT 4",
                    (r, n) -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("family", r.getString(1));
                        item.put("cursor", r.getString(2));
                        item.put("completedPasses", Long.toString(r.getLong(3)));
                        item.put("failureCount", Long.toString(r.getLong(4)));
                        item.put("lastError", r.getString(5));
                        return item;
                    });
            if (backfill.size() != 3) throw new IllegalStateException("Missing backfill state");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "AVAILABLE");
            out.put("states", states);
            out.put("backfill", backfill);
            out.put("note", "SENT indicates stream append, not consumer completion");
            return out;
        } catch (RuntimeException failure) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "UNAVAILABLE");
            out.put("reason", "Content producer delivery state unavailable");
            return out;
        }
    }

    private Map<String, Object> readLocalMetadataSummary() {
        try {
            Long jobs = db.queryForObject("SELECT COUNT(*) FROM content_metadata_job", Long.class);
            Map<String, Object> targetStates = new LinkedHashMap<>();
            for (String state : List.of("PENDING", "DONE", "FAILED")) targetStates.put(state, "0");
            db.query("SELECT state,COUNT(*) FROM content_metadata_target GROUP BY state LIMIT 5", rs -> {
                String state = rs.getString(1);
                targetStates.put(state, Long.toString(rs.getLong(2)));
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "AVAILABLE");
            out.put("jobCount", Long.toString(jobs == null ? 0L : jobs));
            out.put("targetStates", targetStates);
            return out;
        } catch (RuntimeException failure) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "UNAVAILABLE");
            out.put("reason", "Content metadata compensation state unavailable");
            return out;
        }
    }

    private static String resolveReplayRequestId(String provided) {
        if (provided == null) return UUID.randomUUID().toString();
        if (!OriginPolicy.validReplayRequestId(provided)) {
            throw new FacadeFailure(400, 400, "Invalid replayRequestId");
        }
        return provided;
    }

    private static void validateUuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw new FacadeFailure(400, 400, "Invalid eventId");
            }
        } catch (IllegalArgumentException invalid) {
            throw new FacadeFailure(400, 400, "Invalid eventId");
        }
    }

    static String deriveBatchReplayId(String batchReplayId, String eventId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("v1\n" + batchReplayId + "\n" + eventId).getBytes(StandardCharsets.UTF_8));
            return "batch-" + HexFormat.of().formatHex(digest).substring(0, 56);
        } catch (Exception failure) {
            throw new IllegalStateException("Batch replay key digest unavailable");
        }
    }

    private static int parsePositiveInt(String text, int fallback) {
        if (text == null || text.isEmpty()) return fallback;
        if (!text.matches("[1-9][0-9]{0,9}")) {
            throw new FacadeFailure(400, 400, "Invalid pagination");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw new FacadeFailure(400, 400, "Invalid pagination");
        }
    }
}
