package com.yan.constants;

public final class AuthConstants {
    public static final String TOKEN_HEADER = "token";
    public static final String SCOPE_CLAIM = "scope";
    public static final String BLOG_SCOPE = "blog";
    public static final String ADMIN_SCOPE = "admin";
    public static final String BLOG_AUTHORITY = "SCOPE_BLOG";
    public static final String ADMIN_AUTHORITY = "SCOPE_ADMIN";

    private static final String LOGIN_CACHE_PREFIX = "login:";

    private AuthConstants() {
    }

    public static String loginCacheKey(String scope, String userId) {
        return LOGIN_CACHE_PREFIX + scope + ":" + userId;
    }

    public static String authorityFor(String scope) {
        if (ADMIN_SCOPE.equals(scope)) {
            return ADMIN_AUTHORITY;
        }
        if (BLOG_SCOPE.equals(scope)) {
            return BLOG_AUTHORITY;
        }
        throw new IllegalArgumentException("Unsupported login scope: " + scope);
    }
}
