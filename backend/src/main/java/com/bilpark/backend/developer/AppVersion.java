package com.bilpark.backend.developer;

import java.util.Optional;

/** App version as the mobile app reports it: "1.0.0+1" (semver + build number). Compared numerically. */
public record AppVersion(int major, int minor, int patch, int build) implements Comparable<AppVersion> {

    /** Empty when the text is not a version (garbage header values are simply ignored). */
    public static Optional<AppVersion> parse(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.trim();
        if (s.isEmpty()) return Optional.empty();
        String core = s;
        int build = 0;
        int plus = s.indexOf('+');
        try {
            if (plus >= 0) {
                core = s.substring(0, plus);
                build = Integer.parseInt(s.substring(plus + 1).trim());
            }
            int dash = core.indexOf('-'); // "1.0.0-beta" compares as 1.0.0
            if (dash >= 0) core = core.substring(0, dash);
            String[] p = core.trim().split("\\.");
            if (p.length == 0 || p.length > 3) return Optional.empty();
            int[] n = new int[3];
            for (int i = 0; i < p.length; i++) {
                n[i] = Integer.parseInt(p[i].trim());
                if (n[i] < 0) return Optional.empty();
            }
            if (build < 0) return Optional.empty();
            return Optional.of(new AppVersion(n[0], n[1], n[2], build));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(AppVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) c = Integer.compare(minor, o.minor);
        if (c == 0) c = Integer.compare(patch, o.patch);
        if (c == 0) c = Integer.compare(build, o.build);
        return c;
    }
}
