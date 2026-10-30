package com.knode.gateway;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reject path representations that could be interpreted differently by an upstream server. */
final class PathBoundary {
    private static final Pattern ENCODED_SEPARATOR = Pattern.compile("(?i)%2f|%5c|%00");
    private static final Pattern ENCODED_AGAIN = Pattern.compile("(?i)%[0-9a-f]{2}");
    private PathBoundary() {}

    static boolean isSafe(URI uri) {
        String raw = uri.getRawPath();
        String decoded = uri.getPath();
        if (raw == null || decoded == null || ENCODED_SEPARATOR.matcher(raw).find()
                || decoded.indexOf('\\') >= 0 || decoded.indexOf(';') >= 0
                || decoded.indexOf('\uFFFD') >= 0 || decoded.contains("//")
                || ENCODED_AGAIN.matcher(decoded).find()) {
            return false;
        }
        for (String segment : decoded.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")
                    || segment.toLowerCase(Locale.ROOT).equals("internal")
                    || segment.chars().anyMatch(Character::isISOControl)) {
                return false;
            }
        }
        return true;
    }
}
