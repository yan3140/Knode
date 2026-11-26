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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LoopbackRedisStub implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Map<String, String> injectedErrors = new ConcurrentHashMap<>();
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
        injectedErrors.put(command.toUpperCase(Locale.ROOT), errorMessage);
    }

    public void clearFaults() {
        injectedErrors.clear();
    }

    public void clearData() {
        store.clear();
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
        String direct = injectedErrors.get(op);
        if (direct != null) {
            return direct;
        }
        if (("SET".equals(op) || "SETEX".equals(op) || "PSETEX".equals(op)) && injectedErrors.containsKey("WRITE")) {
            return injectedErrors.get("WRITE");
        }
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
            default -> writeError(out, "ERR unknown command '" + op + "'");
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
}
