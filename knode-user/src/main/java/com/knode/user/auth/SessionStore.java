package com.knode.user.auth;

import java.util.Optional;

/** Read-only adapters can implement Reader without receiving write privileges. */
public final class SessionStore {
    private SessionStore() {}
    public record Session(int schemaVersion, String sid, String userId, String scope,
                          String status, long expiresAt) {}
    public interface Reader {
        Optional<Session> find(String sid) throws Unavailable;
    }
    public interface Writer {
        void create(Session session) throws Unavailable;
        void revoke(String sid) throws Unavailable;
    }
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
        public Unavailable(String message, Throwable cause) { super(message, cause); }
    }
}
