package com.knode.search.support;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class LoopbackRedisStub implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    private final Map<String, FaultRule> injectedErrors = new ConcurrentHashMap<>();
    private final List<List<String>> recordedCommands = new CopyOnWriteArrayList<>();

    public LoopbackRedisStub() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.executor = Executors.newCachedThreadPool();
        this.executor.submit(this::acceptLoop);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void failCommand(String command, String errorMessage) {
        injectedErrors.put(command.toUpperCase(Locale.ROOT), new FaultRule(errorMessage, new AtomicInteger(-1)));
    }

    public void failCommandTimes(String command, String errorMessage, int times) {
        injectedErrors.put(command.toUpperCase(Locale.ROOT), new FaultRule(errorMessage, new AtomicInteger(times)));
    }

    public void clearFaults() {
        injectedErrors.clear();
    }

    public void clearData() {
        store.clear();
        streams.clear();
    }

    public void clearHistory() {
        recordedCommands.clear();
    }

    public void reset() {
        clearFaults();
        clearData();
        clearHistory();
    }

    public void putRaw(String key, String value) {
        store.put(key, new Entry(value, Long.MAX_VALUE));
    }

    public String getRaw(String key) {
        Entry entry = getActiveEntry(key);
        return entry == null ? null : entry.value();
    }

    public boolean containsKey(String key) {
        return getActiveEntry(key) != null;
    }

    public Map<String, String> snapshot() {
        Map<String, String> result = new ConcurrentHashMap<>();
        for (String key : store.keySet()) {
            Entry active = getActiveEntry(key);
            if (active != null) {
                result.put(key, active.value());
            }
        }
        return result;
    }

    public List<List<String>> recordedCommands() {
        return List.copyOf(recordedCommands);
    }

    // --- Test-harness Stream helpers (group setup & inspection only) ---

    public void createGroup(String streamKey, String groupName) {
        StreamState state = streams.computeIfAbsent(streamKey, k -> new StreamState());
        synchronized (state) {
            state.groups.computeIfAbsent(groupName, g -> new GroupState());
        }
    }

    public String addStreamMessage(String streamKey, Map<String, String> fields) {
        StreamState state = streams.computeIfAbsent(streamKey, k -> new StreamState());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (now > state.lastMs) {
                state.lastMs = now;
                state.lastSeq = 0L;
            } else {
                state.lastSeq++;
            }
            String id = state.lastMs + "-" + state.lastSeq;
            state.entries.add(new StreamEntry(id, new LinkedHashMap<>(fields)));
            return id;
        }
    }

    public int streamLength(String streamKey) {
        StreamState state = streams.get(streamKey);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.entries.size();
        }
    }

    public int pendingCount(String streamKey, String groupName) {
        StreamState state = streams.get(streamKey);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            GroupState group = state.groups.get(groupName);
            return group == null ? 0 : group.pending.size();
        }
    }

    public List<String> pendingRecordIds(String streamKey, String groupName) {
        StreamState state = streams.get(streamKey);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            GroupState group = state.groups.get(groupName);
            if (group == null) {
                return List.of();
            }
            return List.copyOf(group.pending.keySet());
        }
    }

    public String pendingConsumerOf(String streamKey, String groupName, String recordId) {
        StreamState state = streams.get(streamKey);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            GroupState group = state.groups.get(groupName);
            if (group == null) {
                return null;
            }
            PendingRecord rec = group.pending.get(recordId);
            return rec == null ? null : rec.consumer;
        }
    }

    public void agePendingEntries(String streamKey, String groupName, long deltaMs) {
        StreamState state = streams.get(streamKey);
        if (state == null) {
            return;
        }
        synchronized (state) {
            GroupState group = state.groups.get(groupName);
            if (group != null) {
                for (PendingRecord rec : group.pending.values()) {
                    rec.deliveredAtMs -= deltaMs;
                }
            }
        }
    }

    public void agePendingEntry(String streamKey, String groupName, String recordId, long deltaMs) {
        StreamState state = streams.get(streamKey);
        if (state == null) {
            return;
        }
        synchronized (state) {
            GroupState group = state.groups.get(groupName);
            if (group != null) {
                PendingRecord rec = group.pending.get(recordId);
                if (rec != null) {
                    rec.deliveredAtMs -= deltaMs;
                }
            }
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handleConnection(socket));
            } catch (IOException ex) {
                if (running.get()) {
                    break;
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket client = socket;
             InputStream in = new BufferedInputStream(client.getInputStream());
             OutputStream out = new BufferedOutputStream(client.getOutputStream())) {
            while (running.get()) {
                List<String> command = readCommand(in);
                if (command == null || command.isEmpty()) {
                    return;
                }
                String op = command.get(0).toUpperCase(Locale.ROOT);
                recordedCommands.add(List.copyOf(command));
                if ("QUIT".equals(op)) {
                    writeSimpleString(out, "OK");
                    out.flush();
                    return;
                }
                String fault = resolveFault(op);
                if (fault != null) {
                    writeError(out, fault);
                    out.flush();
                    continue;
                }
                dispatch(op, command, out);
                out.flush();
            }
        } catch (IOException ignored) {
            // Connection closed by client or test teardown.
        }
    }

    private String resolveFault(String op) {
        String direct = consumeFault(op);
        if (direct != null) {
            return direct;
        }
        if ("SET".equals(op) || "SETEX".equals(op) || "PSETEX".equals(op)) {
            return consumeFault("WRITE");
        }
        return null;
    }

    private String consumeFault(String key) {
        FaultRule rule = injectedErrors.get(key);
        if (rule == null) {
            return null;
        }
        int remaining = rule.remaining().get();
        if (remaining < 0) {
            return rule.message();
        }
        while (remaining > 0) {
            if (rule.remaining().compareAndSet(remaining, remaining - 1)) {
                if (remaining - 1 == 0) {
                    injectedErrors.remove(key, rule);
                }
                return rule.message();
            }
            remaining = rule.remaining().get();
        }
        injectedErrors.remove(key, rule);
        return null;
    }

    private void dispatch(String op, List<String> args, OutputStream out) throws IOException {
        switch (op) {
            case "HELLO" -> writeError(out, "ERR unknown command 'HELLO'");
            case "PING" -> writeSimpleString(out, args.size() > 1 ? args.get(1) : "PONG");
            case "AUTH", "SELECT" -> writeSimpleString(out, "OK");
            case "CLIENT" -> {
                if (args.size() > 1 && "ID".equalsIgnoreCase(args.get(1))) {
                    writeInteger(out, 1L);
                } else {
                    writeSimpleString(out, "OK");
                }
            }
            case "GET" -> {
                Entry entry = args.size() > 1 ? getActiveEntry(args.get(1)) : null;
                writeBulkString(out, entry == null ? null : entry.value());
            }
            case "SET" -> handleSet(args, out);
            case "SETEX" -> {
                if (args.size() < 4) {
                    writeError(out, "ERR wrong number of arguments for 'setex'");
                } else {
                    long ttlMs = Long.parseLong(args.get(2)) * 1000L;
                    store.put(args.get(1), new Entry(args.get(3), System.currentTimeMillis() + Math.max(1L, ttlMs)));
                    writeSimpleString(out, "OK");
                }
            }
            case "PSETEX" -> {
                if (args.size() < 4) {
                    writeError(out, "ERR wrong number of arguments for 'psetex'");
                } else {
                    long ttlMs = Long.parseLong(args.get(2));
                    store.put(args.get(1), new Entry(args.get(3), System.currentTimeMillis() + Math.max(1L, ttlMs)));
                    writeSimpleString(out, "OK");
                }
            }
            case "INCR" -> {
                if (args.size() < 2) {
                    writeError(out, "ERR wrong number of arguments for 'incr'");
                } else {
                    String key = args.get(1);
                    Entry updated = store.compute(key, (k, existing) -> {
                        long now = System.currentTimeMillis();
                        if (existing == null || existing.expiresAtMs() <= now) {
                            return new Entry("1", Long.MAX_VALUE);
                        }
                        long next = Long.parseLong(existing.value()) + 1L;
                        return new Entry(Long.toString(next), existing.expiresAtMs());
                    });
                    writeInteger(out, Long.parseLong(updated.value()));
                }
            }
            case "EXPIRE" -> handleExpire(args, 1000L, out);
            case "PEXPIRE" -> handleExpire(args, 1L, out);
            case "DEL" -> {
                long removed = 0L;
                for (int i = 1; i < args.size(); i++) {
                    if (store.remove(args.get(i)) != null) {
                        removed++;
                    }
                }
                writeInteger(out, removed);
            }
            case "EVALSHA" -> writeError(out, "NOSCRIPT No matching script. Please use EVAL.");
            case "EVAL" -> handleEval(args, out);
            case "XREADGROUP" -> handleXReadGroup(args, out);
            case "XPENDING" -> handleXPending(args, out);
            case "XCLAIM" -> handleXClaim(args, out);
            case "XACK" -> handleXAck(args, out);
            default -> writeError(out, "ERR unknown command '" + op + "'");
        }
    }

    private void handleEval(List<String> args, OutputStream out) throws IOException {
        if (args.size() < 3) {
            writeError(out, "ERR wrong number of arguments for 'eval'");
            return;
        }
        String script = args.get(1);
        int numKeys;
        try {
            numKeys = Integer.parseInt(args.get(2));
        } catch (NumberFormatException ex) {
            writeError(out, "ERR value is not an integer or out of range");
            return;
        }
        if (numKeys == 1 && args.size() >= 5
                && script.contains("redis.call('GET', KEYS[1]) == ARGV[1]")
                && script.contains("redis.call('DEL', KEYS[1])")) {
            String key = args.get(3);
            String expected = args.get(4);
            AtomicBoolean deleted = new AtomicBoolean(false);
            store.compute(key, (k, existing) -> {
                if (existing == null || existing.expiresAtMs() <= System.currentTimeMillis()) {
                    return null;
                }
                if (expected.equals(existing.value())) {
                    deleted.set(true);
                    return null;
                }
                return existing;
            });
            writeInteger(out, deleted.get() ? 1L : 0L);
            return;
        }
        if (numKeys == 1 && args.size() >= 7
                && script.contains("redis.call('SET', KEYS[1], ARGV[1])")
                && script.contains("string.match(cur, ':g(%d+):a(%d+)$')")) {
            String key = args.get(3);
            String newOwner = args.get(4);
            long newGen = Long.parseLong(args.get(5));
            long newAttempt = Long.parseLong(args.get(6));
            AtomicBoolean written = new AtomicBoolean(false);
            store.compute(key, (k, existing) -> {
                long now = System.currentTimeMillis();
                if (existing == null || existing.expiresAtMs() <= now) {
                    written.set(true);
                    return new Entry(newOwner, Long.MAX_VALUE);
                }
                long[] parsed = parseBypassOwnerRank(existing.value());
                if (parsed == null || newGen > parsed[0] || (newGen == parsed[0] && newAttempt >= parsed[1])) {
                    written.set(true);
                    return new Entry(newOwner, Long.MAX_VALUE);
                }
                return existing;
            });
            writeInteger(out, written.get() ? 1L : 0L);
            return;
        }
        writeError(out, "ERR unsupported eval script in loopback stub");
    }

    private static long[] parseBypassOwnerRank(String value) {
        if (value == null) {
            return null;
        }
        int gIdx = value.lastIndexOf(":g");
        if (gIdx < 0) {
            return null;
        }
        String tail = value.substring(gIdx + 2);
        int aIdx = tail.indexOf(":a");
        try {
            if (aIdx >= 0) {
                long gen = Long.parseLong(tail.substring(0, aIdx));
                long attempt = Long.parseLong(tail.substring(aIdx + 2));
                return new long[]{gen, attempt};
            }
            long gen = Long.parseLong(tail);
            return new long[]{gen, 0L};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void handleXReadGroup(List<String> args, OutputStream out) throws IOException {
        int idx = 1;
        if (idx + 2 >= args.size() || !"GROUP".equalsIgnoreCase(args.get(idx))) {
            writeError(out, "ERR syntax error");
            return;
        }
        String groupName = args.get(idx + 1);
        String consumerName = args.get(idx + 2);
        idx += 3;
        int count = Integer.MAX_VALUE;
        while (idx < args.size()) {
            String token = args.get(idx).toUpperCase(Locale.ROOT);
            if ("COUNT".equals(token) && idx + 1 < args.size()) {
                count = Integer.parseInt(args.get(idx + 1));
                idx += 2;
            } else if ("BLOCK".equals(token) && idx + 1 < args.size()) {
                idx += 2;
            } else if ("STREAMS".equals(token)) {
                idx++;
                break;
            } else {
                idx++;
            }
        }
        if (idx + 1 >= args.size()) {
            writeError(out, "ERR syntax error in XREADGROUP");
            return;
        }
        String streamKey = args.get(idx);
        String idArg = args.get(idx + 1);

        StreamState state = streams.get(streamKey);
        if (state == null) {
            writeError(out, "NOGROUP No such key '" + streamKey + "' or consumer group '" + groupName + "'");
            return;
        }
        List<StreamEntry> delivered = new ArrayList<>();
        synchronized (state) {
            GroupState group = state.groups.get(groupName);
            if (group == null) {
                writeError(out, "NOGROUP No such consumer group '" + groupName + "'");
                return;
            }
            if (">".equals(idArg)) {
                long now = System.currentTimeMillis();
                while (group.nextIndex < state.entries.size() && delivered.size() < count) {
                    StreamEntry entry = state.entries.get(group.nextIndex++);
                    delivered.add(entry);
                    group.pending.put(entry.id(), new PendingRecord(entry.id(), consumerName, now, 1));
                }
            }
        }
        if (delivered.isEmpty()) {
            writeNullArray(out);
            return;
        }
        writeArrayHeader(out, 1);
        writeArrayHeader(out, 2);
        writeBulkString(out, streamKey);
        writeStreamEntries(out, delivered);
    }

    private void handleXPending(List<String> args, OutputStream out) throws IOException {
        // XPENDING <stream> <group> [IDLE min] <start> <end> <count>
        if (args.size() < 6) {
            writeError(out, "ERR wrong number of arguments for 'xpending'");
            return;
        }
        String streamKey = args.get(1);
        String groupName = args.get(2);
        int idx = 3;
        long minIdleFilter = -1L;
        if ("IDLE".equalsIgnoreCase(args.get(idx)) && idx + 1 < args.size()) {
            minIdleFilter = Long.parseLong(args.get(idx + 1));
            idx += 2;
        }
        if (idx + 2 >= args.size()) {
            writeError(out, "ERR syntax error in XPENDING");
            return;
        }
        String startSpec = args.get(idx);
        String endSpec = args.get(idx + 1);
        int count = Integer.parseInt(args.get(idx + 2));

        StreamState state = streams.get(streamKey);
        if (state == null) {
            writeError(out, "NOGROUP No such key '" + streamKey + "'");
            return;
        }
        List<PendingSnapshot> items = new ArrayList<>();
        synchronized (state) {
            GroupState group = state.groups.get(groupName);
            if (group == null) {
                writeError(out, "NOGROUP No such consumer group '" + groupName + "'");
                return;
            }
            long now = System.currentTimeMillis();
            List<PendingRecord> sorted = new ArrayList<>(group.pending.values());
            sorted.sort((a, b) -> compareStreamIds(a.id, b.id));
            for (PendingRecord rec : sorted) {
                if (!matchesLowerBound(rec.id, startSpec) || !matchesUpperBound(rec.id, endSpec)) {
                    continue;
                }
                long idle = Math.max(0L, now - rec.deliveredAtMs);
                if (minIdleFilter >= 0L && idle < minIdleFilter) {
                    continue;
                }
                items.add(new PendingSnapshot(rec.id, rec.consumer, idle, rec.deliveryCount));
                if (items.size() >= count) {
                    break;
                }
            }
        }
        writeArrayHeader(out, items.size());
        for (PendingSnapshot item : items) {
            writeArrayHeader(out, 4);
            writeBulkString(out, item.id());
            writeBulkString(out, item.consumer());
            writeInteger(out, item.idleMs());
            writeInteger(out, item.deliveryCount());
        }
    }

    private static boolean matchesLowerBound(String id, String startSpec) {
        if (startSpec == null || "-".equals(startSpec)) {
            return true;
        }
        if (startSpec.startsWith("(")) {
            return compareStreamIds(id, startSpec.substring(1)) > 0;
        }
        return compareStreamIds(id, startSpec) >= 0;
    }

    private static boolean matchesUpperBound(String id, String endSpec) {
        if (endSpec == null || "+".equals(endSpec)) {
            return true;
        }
        if (endSpec.startsWith("(")) {
            return compareStreamIds(id, endSpec.substring(1)) < 0;
        }
        return compareStreamIds(id, endSpec) <= 0;
    }

    private static int compareStreamIds(String a, String b) {
        long[] pa = parseStreamId(a);
        long[] pb = parseStreamId(b);
        int cmp = Long.compareUnsigned(pa[0], pb[0]);
        return cmp != 0 ? cmp : Long.compareUnsigned(pa[1], pb[1]);
    }

    private static long[] parseStreamId(String id) {
        int dash = id.indexOf('-');
        if (dash < 0) {
            return new long[]{Long.parseUnsignedLong(id), 0L};
        }
        return new long[]{
                Long.parseUnsignedLong(id.substring(0, dash)),
                Long.parseUnsignedLong(id.substring(dash + 1))
        };
    }

    private void handleXClaim(List<String> args, OutputStream out) throws IOException {
        if (args.size() < 6) {
            writeError(out, "ERR wrong number of arguments for 'xclaim'");
            return;
        }
        String streamKey = args.get(1);
        String groupName = args.get(2);
        String consumerName = args.get(3);
        long minIdleMs = Long.parseLong(args.get(4));

        StreamState state = streams.get(streamKey);
        if (state == null) {
            writeError(out, "NOGROUP No such key '" + streamKey + "'");
            return;
        }
        List<StreamEntry> claimed = new ArrayList<>();
        synchronized (state) {
            GroupState group = state.groups.get(groupName);
            if (group == null) {
                writeError(out, "NOGROUP No such consumer group '" + groupName + "'");
                return;
            }
            long now = System.currentTimeMillis();
            for (int i = 5; i < args.size(); i++) {
                String id = args.get(i);
                PendingRecord rec = group.pending.get(id);
                if (rec == null) {
                    continue;
                }
                long idle = Math.max(0L, now - rec.deliveredAtMs);
                if (idle < minIdleMs) {
                    continue;
                }
                rec.consumer = consumerName;
                rec.deliveredAtMs = now;
                rec.deliveryCount++;
                StreamEntry entry = findStreamEntry(state, id);
                if (entry != null) {
                    claimed.add(entry);
                }
            }
        }
        writeStreamEntries(out, claimed);
    }

    private void handleXAck(List<String> args, OutputStream out) throws IOException {
        if (args.size() < 4) {
            writeError(out, "ERR wrong number of arguments for 'xack'");
            return;
        }
        String streamKey = args.get(1);
        String groupName = args.get(2);
        StreamState state = streams.get(streamKey);
        if (state == null) {
            writeInteger(out, 0L);
            return;
        }
        long acked = 0L;
        synchronized (state) {
            GroupState group = state.groups.get(groupName);
            if (group != null) {
                for (int i = 3; i < args.size(); i++) {
                    if (group.pending.remove(args.get(i)) != null) {
                        acked++;
                    }
                }
            }
        }
        writeInteger(out, acked);
    }

    private static StreamEntry findStreamEntry(StreamState state, String id) {
        for (StreamEntry entry : state.entries) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private static void writeStreamEntries(OutputStream out, List<StreamEntry> entries) throws IOException {
        writeArrayHeader(out, entries.size());
        for (StreamEntry entry : entries) {
            writeArrayHeader(out, 2);
            writeBulkString(out, entry.id());
            writeArrayHeader(out, entry.fields().size() * 2);
            for (Map.Entry<String, String> kv : entry.fields().entrySet()) {
                writeBulkString(out, kv.getKey());
                writeBulkString(out, kv.getValue());
            }
        }
    }

    private void handleSet(List<String> args, OutputStream out) throws IOException {
        if (args.size() < 3) {
            writeError(out, "ERR wrong number of arguments for 'set'");
            return;
        }
        String key = args.get(1);
        String value = args.get(2);
        long expiresAt = Long.MAX_VALUE;
        for (int i = 3; i < args.size(); i++) {
            String flag = args.get(i).toUpperCase(Locale.ROOT);
            if ("EX".equals(flag) && i + 1 < args.size()) {
                expiresAt = System.currentTimeMillis() + Math.max(1L, Long.parseLong(args.get(++i)) * 1000L);
            } else if ("PX".equals(flag) && i + 1 < args.size()) {
                expiresAt = System.currentTimeMillis() + Math.max(1L, Long.parseLong(args.get(++i)));
            }
        }
        store.put(key, new Entry(value, expiresAt));
        writeSimpleString(out, "OK");
    }

    private void handleExpire(List<String> args, long multiplierMs, OutputStream out) throws IOException {
        if (args.size() < 3) {
            writeError(out, "ERR wrong number of arguments for 'expire'");
            return;
        }
        String key = args.get(1);
        long durationMs = Long.parseLong(args.get(2)) * multiplierMs;
        Entry entry = getActiveEntry(key);
        if (entry == null) {
            writeInteger(out, 0L);
            return;
        }
        store.put(key, new Entry(entry.value(), System.currentTimeMillis() + Math.max(1L, durationMs)));
        writeInteger(out, 1L);
    }

    private Entry getActiveEntry(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMs() <= System.currentTimeMillis()) {
            store.remove(key, entry);
            return null;
        }
        return entry;
    }

    private static List<String> readCommand(InputStream in) throws IOException {
        int prefix = in.read();
        if (prefix == -1) {
            return null;
        }
        if (prefix == '*') {
            String countLine = readLine(in);
            if (countLine == null) {
                return null;
            }
            int count = Integer.parseInt(countLine);
            List<String> args = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                int bulkPrefix = in.read();
                if (bulkPrefix != '$') {
                    throw new IOException("Expected bulk string '$' but got " + bulkPrefix);
                }
                String lengthLine = readLine(in);
                if (lengthLine == null) {
                    return null;
                }
                int len = Integer.parseInt(lengthLine);
                byte[] buf = in.readNBytes(len);
                if (buf.length != len) {
                    return null;
                }
                int cr = in.read();
                int lf = in.read();
                if (cr != '\r' || lf != '\n') {
                    throw new IOException("Malformed CRLF after bulk string");
                }
                args.add(new String(buf, StandardCharsets.UTF_8));
            }
            return args;
        }
        ByteArrayOutputStream inline = new ByteArrayOutputStream();
        inline.write(prefix);
        String rest = readLine(in);
        if (rest != null) {
            inline.write(rest.getBytes(StandardCharsets.UTF_8));
        }
        String line = inline.toString(StandardCharsets.UTF_8).trim();
        return line.isEmpty() ? List.of() : List.of(line.split("\\s+"));
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int curr = in.read();
            if (curr == -1) {
                return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
            }
            if (prev == '\r' && curr == '\n') {
                byte[] bytes = buf.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(curr);
            prev = curr;
        }
    }

    private static void writeSimpleString(OutputStream out, String value) throws IOException {
        out.write(('+' + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeError(OutputStream out, String message) throws IOException {
        out.write(('-' + message + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeInteger(OutputStream out, long value) throws IOException {
        out.write((':' + Long.toString(value) + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeArrayHeader(OutputStream out, int size) throws IOException {
        out.write(('*' + Integer.toString(size) + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeNullArray(OutputStream out) throws IOException {
        out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBulkString(OutputStream out, String value) throws IOException {
        if (value == null) {
            out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(('$' + Integer.toString(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        serverSocket.close();
        executor.shutdownNow();
    }

    private record Entry(String value, long expiresAtMs) {
    }

    private record FaultRule(String message, AtomicInteger remaining) {
    }

    private record StreamEntry(String id, Map<String, String> fields) {
    }

    private record PendingSnapshot(String id, String consumer, long idleMs, long deliveryCount) {
    }

    private static final class PendingRecord {
        final String id;
        String consumer;
        long deliveredAtMs;
        long deliveryCount;

        PendingRecord(String id, String consumer, long deliveredAtMs, long deliveryCount) {
            this.id = id;
            this.consumer = consumer;
            this.deliveredAtMs = deliveredAtMs;
            this.deliveryCount = deliveryCount;
        }
    }

    private static final class GroupState {
        int nextIndex = 0;
        final Map<String, PendingRecord> pending = new LinkedHashMap<>();
    }

    private static final class StreamState {
        long lastMs = 0L;
        long lastSeq = 0L;
        final List<StreamEntry> entries = new ArrayList<>();
        final Map<String, GroupState> groups = new LinkedHashMap<>();
    }
}
