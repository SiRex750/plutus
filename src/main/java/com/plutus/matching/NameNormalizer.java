package com.plutus.matching;

import java.util.List;
import java.util.Locale;

/**
 * Canonical form of a person or business name, shared by the name matchers:
 * uppercase, "&" read as "AND", apostrophes removed (D'SOUZA → DSOUZA), other punctuation turned into
 * spaces, spaces collapsed, and common business suffixes dropped (PVT LTD, LTD, LLP, AND SONS, ...).
 */
public final class NameNormalizer {

    private static final List<String> SUFFIXES = List.of(
            " PRIVATE LIMITED", " PRIVATE LTD", " PVT LIMITED", " PVT LTD", " LIMITED", " LTD", " LLP", " AND SONS");

    private NameNormalizer() {
    }

    /** Canonical form; empty string for null/blank input. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String n = name.toUpperCase(Locale.ROOT)
                .replace("&", " AND ")
                .replaceAll("['’]", "")
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : SUFFIXES) {
                if (n.endsWith(suffix)) {
                    n = n.substring(0, n.length() - suffix.length()).strip();
                    changed = true;
                }
            }
        }
        return n;
    }
}
