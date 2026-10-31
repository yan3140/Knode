package com.knode.user.auth;

/** Disabled means no event publishing is claimed or attempted. */
public final class DisabledSessionEventTransport implements SessionEventTransport {
    @Override public boolean enabled() {return false;}
    @Override public void publish(String envelope) {throw new IllegalStateException("Session event publishing disabled");}
    @Override public boolean probe() {return false;}
    @Override public void close() {}
}
