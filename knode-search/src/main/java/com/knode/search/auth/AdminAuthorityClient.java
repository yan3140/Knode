package com.knode.search.auth;

import java.util.Set;

public interface AdminAuthorityClient {

    /**
     * Fetches fresh current administrator identity and active permissions from User /api/admin/auth/me.
     * Throws com.knode.auth.AuthFailure(401/403/503) on invalid/revoked/forbidden/unavailable authority.
     */
    AdminAuthority fetchCurrentAuthority(String rawAdminToken, String requestId);

    record AdminAuthority(String userId, Set<String> permissions) {
    }
}
