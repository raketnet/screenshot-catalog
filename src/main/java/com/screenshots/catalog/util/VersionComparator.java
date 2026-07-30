package com.screenshots.catalog.util;

import java.util.Comparator;

/**
 * Compares version strings like "6.2.0", "v6.10.1", "1.2" numerically segment by
 * segment, instead of lexicographically (which would wrongly sort "6.10.0" before
 * "6.9.0"). Falls back to plain string comparison for segments that aren't numeric.
 */
public final class VersionComparator implements Comparator<String> {

    public static final VersionComparator INSTANCE = new VersionComparator();

    @Override
    public int compare(String a, String b) {
        String[] left = normalize(a).split("\\.");
        String[] right = normalize(b).split("\\.");

        int len = Math.max(left.length, right.length);
        for (int i = 0; i < len; i++) {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";

            int cmp;
            if (l.matches("\\d+") && r.matches("\\d+")) {
                cmp = Integer.compare(Integer.parseInt(l), Integer.parseInt(r));
            } else {
                cmp = l.compareTo(r);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private String normalize(String version) {
        return version.startsWith("v") || version.startsWith("V")
                ? version.substring(1)
                : version;
    }
}
