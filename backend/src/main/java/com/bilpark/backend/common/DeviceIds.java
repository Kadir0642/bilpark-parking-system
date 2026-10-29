package com.bilpark.backend.common;

import java.util.regex.Pattern;

/** X-Device-Id header sanitizer: 1-64 chars of [A-Za-z0-9._-], otherwise "unknown". */
public final class DeviceIds {
    private DeviceIds() {}

    public static final String UNKNOWN = "unknown";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public static String sanitize(String raw) {
        if (raw == null) return UNKNOWN;
        String s = raw.trim();
        return VALID.matcher(s).matches() ? s : UNKNOWN;
    }
}
