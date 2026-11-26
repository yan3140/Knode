package com.knode.search.auth;

import java.util.Set;

public record AdminPrincipal(String userId, String sid, Set<String> permissions) {
}
