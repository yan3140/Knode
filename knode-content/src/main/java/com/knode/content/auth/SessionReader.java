package com.knode.content.auth;

import java.util.Optional;

/** Read-only, authoritative session lookup. Missing and unavailable must remain distinct. */
public interface SessionReader {
    Optional<String> read(String sid);
}
