package com.knode.search.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class IdentityHeaderGuard {
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "x-internal-",
            "x-user-",
            "x-principal-",
            "x-service-",
            "x-identity-",
            "x-authenticated-",
            "x-viewer-",
            "x-actor-"
    );
    private static final Set<String> FORBIDDEN_EXACT = Set.of(
            "x-forwarded-user",
            "x-remote-user",
            "x-auth-request-user"
    );

    private IdentityHeaderGuard() {
    }

    public static boolean hasForgedIdentityHeader(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return false;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name == null) {
                continue;
            }
            String lower = name.trim().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_EXACT.contains(lower)) {
                return true;
            }
            for (String prefix : FORBIDDEN_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasAnyHeader(HttpServletRequest request, String headerName) {
        Enumeration<String> values = request.getHeaders(headerName);
        return values != null && values.hasMoreElements();
    }

    public static String extractSingleNonBlankHeader(HttpServletRequest request, String headerName) {
        Enumeration<String> values = request.getHeaders(headerName);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String first = values.nextElement();
        if (values.hasMoreElements()) {
            return null;
        }
        if (first == null) {
            return null;
        }
        String trimmed = first.trim();
        if (trimmed.isEmpty() || trimmed.contains(",")) {
            return null;
        }
        return trimmed;
    }

    public static String extractSingleBearerToken(HttpServletRequest request) {
        String header = extractSingleNonBlankHeader(request, "Authorization");
        if (header == null) {
            return null;
        }
        if (header.length() <= 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty() || token.contains(" ") || token.contains(",")) {
            return null;
        }
        return token;
    }
}
