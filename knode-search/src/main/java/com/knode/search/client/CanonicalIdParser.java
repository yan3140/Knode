package com.knode.search.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

public final class CanonicalIdParser {
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]{0,18}$");

    private CanonicalIdParser() {
    }

    public static Long parsePositiveLongString(String value) {
        if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String normalizePositiveDecimalString(String value) {
        Long parsed = parsePositiveLongString(value);
        return parsed == null ? null : Long.toString(parsed);
    }

    public static String parseCanonicalPositiveFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return normalizePositiveDecimalString(node.asText());
        }
        if (node.isIntegralNumber()) {
            long value = node.longValue();
            return value > 0L ? Long.toString(value) : null;
        }
        return null;
    }

    public static Long parseLongFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
