package com.knode.community.auth;

import java.util.Optional;

public interface SessionReader {
    Optional<String> read(String sid);
}
