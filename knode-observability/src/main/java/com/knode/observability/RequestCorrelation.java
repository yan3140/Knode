package com.knode.observability;

import java.util.UUID;
import java.util.regex.Pattern;

/** Request IDs are correlation only and never represent authenticated identity. */
public final class RequestCorrelation {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private RequestCorrelation() {}
    public static boolean valid(String value) { return value != null && ID.matcher(value).matches(); }
    public static String validatedOrNew(String value) { return valid(value) ? value : UUID.randomUUID().toString(); }
}
