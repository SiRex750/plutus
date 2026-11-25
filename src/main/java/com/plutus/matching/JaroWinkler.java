package com.plutus.matching;

/**
 * Jaro-Winkler string similarity (0..1), implemented from the standard definition.
 * Good at short strings like names: tolerant of small typos and swapped neighbouring letters,
 * and rewards a shared beginning (people rarely misspell the first letters of a name).
 */
public final class JaroWinkler {

    private static final double PREFIX_SCALE = 0.1;
    private static final int MAX_PREFIX = 4;

    private JaroWinkler() {
    }

    public static double similarity(String a, String b) {
        String s1 = a == null ? "" : a;
        String s2 = b == null ? "" : b;
        if (s1.equals(s2)) {
            return 1.0; // identical, including both empty
        }
        if (s1.isEmpty() || s2.isEmpty()) {
            return 0.0;
        }

        // Step 1: characters only "match" if they are equal and not too far apart.
        // The allowed distance is half the longer string, minus one.
        int window = Math.max(0, Math.max(s1.length(), s2.length()) / 2 - 1);
        boolean[] matched1 = new boolean[s1.length()];
        boolean[] matched2 = new boolean[s2.length()];
        int matches = 0;
        for (int i = 0; i < s1.length(); i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(s2.length() - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!matched2[j] && s1.charAt(i) == s2.charAt(j)) {
                    matched1[i] = true;
                    matched2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }

        // Step 2: walk the matched characters of both strings in order; where they disagree, the letters
        // were swapped. Each swap shows up twice, so transpositions = disagreements / 2.
        int disagreements = 0;
        int k = 0;
        for (int i = 0; i < s1.length(); i++) {
            if (matched1[i]) {
                while (!matched2[k]) {
                    k++;
                }
                if (s1.charAt(i) != s2.charAt(k)) {
                    disagreements++;
                }
                k++;
            }
        }
        double transpositions = disagreements / 2.0;

        // Step 3: Jaro = average of (share of s1 matched, share of s2 matched, share of matches in order).
        double m = matches;
        double jaro = (m / s1.length() + m / s2.length() + (m - transpositions) / m) / 3.0;

        // Step 4: Winkler's boost: for each of the first (up to 4) characters both strings share,
        // close 10% of the remaining gap to 1.0.
        int prefix = 0;
        int limit = Math.min(MAX_PREFIX, Math.min(s1.length(), s2.length()));
        while (prefix < limit && s1.charAt(prefix) == s2.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * PREFIX_SCALE * (1.0 - jaro);
    }
}
