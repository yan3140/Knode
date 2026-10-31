package com.knode.user.auth;

/** A successful publish means Redis accepted XADD, not that any consumer processed it. */
public interface SessionEventTransport extends AutoCloseable {
    boolean enabled();
    void publish(String envelope);
    boolean probe();
    @Override void close();
}
