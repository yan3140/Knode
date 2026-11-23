package com.knode.content.relay;

public interface RelayStream extends AutoCloseable {
    boolean enabled();
    String publish(String rawJson);
    @Override default void close() {}
    final class Disabled implements RelayStream {
        @Override public boolean enabled() { return false; }
        @Override public String publish(String rawJson) { throw new IllegalStateException("Relay disabled"); }
    }
}
