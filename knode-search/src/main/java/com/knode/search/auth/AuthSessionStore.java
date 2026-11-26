package com.knode.search.auth;

import com.knode.auth.Session;

import java.util.Optional;

public interface AuthSessionStore {

    /**
     * Loads the authoritative session record by sid.
     * Returns Optional.empty() strictly when Redis confirms key does not exist.
     * Throws AuthFailure(503) when store is unconfigured, unavailable, timed out, or session JSON is malformed.
     */
    Optional<Session> loadSession(String sid);
}
