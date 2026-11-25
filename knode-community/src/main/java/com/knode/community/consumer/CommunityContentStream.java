package com.knode.community.consumer;

public interface CommunityContentStream extends AutoCloseable {
    boolean enabled();

    int pollAndIngestOnce();

    @Override
    default void close() {}

    final class Disabled implements CommunityContentStream {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public int pollAndIngestOnce() {
            return 0;
        }
    }
}
