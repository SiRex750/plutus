package com.plutus.matching;

import java.util.List;
import java.util.Locale;

/**
 * 1.0 if the two names are identical after normalisation, else 0.0.
 * Normalisation: uppercase, "&" read as "AND", punctuation removed, spaces collapsed, and common business
 * suffixes dropped (PVT LTD, LTD, LLP, AND SONS, ...), so "Sharma & Sons" equals "SHARMA AND SONS"
 * and "Acme Traders Pvt. Ltd." equals "ACME TRADERS".
 */
public final class ExactNameMatcher implements NameMatcher {

    private static final List<String> SUFFIXES = List.of(
            " PRIVATE LIMITED", " PRIVATE LTD", " PVT LIMITED", " PVT LTD", " LIMITED", " LTD", " LLP", " AND SONS");

    @Override
    public double similarity(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        return !left.isEmpty() && left.equals(right) ? 1.0 : 0.0;
    }

    /** Canonical form of a name; empty string for null/blank input. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String n = name.toUpperCase(Locale.ROOT)
                .replace("&", " AND ")
                .replaceAll("['’]", "")          // D'SOUZA -> DSOUZA (apostrophes join, not split)
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\s+", " ")
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
