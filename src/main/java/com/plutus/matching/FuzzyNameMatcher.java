package com.plutus.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Token-based fuzzy name matching for Indian bank narrations.
 *
 * <ol>
 *   <li>Normalise with {@link NameNormalizer} and drop leading honorifics (MR, MRS, SHRI, SMT, M/S, ...).</li>
 *   <li>Split into tokens and align them order-independently (KUMAR RAVI ≈ RAVI KUMAR), choosing the
 *       pairing with the highest total score. A pair scores 1.0 if equal, {@value #INITIAL_SCORE} if one
 *       side is an initial of the other (R ≈ RAVI), otherwise its Jaro-Winkler similarity if at least
 *       {@value #MIN_TOKEN_SIMILARITY}; anything lower does not align.</li>
 *   <li>Score = sum of aligned pair scores / the larger token count, so unmatched extra tokens lower it.</li>
 * </ol>
 * The UPI handle's local part (ravi.k@okhdfcbank → RAVI K) is a second, slightly weaker signal.
 */
public final class FuzzyNameMatcher implements NameMatcher {

    /**
     * An initial is real but weak evidence. Chosen so that "R KUMAR" vs "RAVI KUMAR" scores 0.875:
     * in the "looks similar, ask a human" band (0.70-0.90), not the auto-confirm band.
     */
    static final double INITIAL_SCORE = 0.75;
    static final double MIN_TOKEN_SIMILARITY = 0.85;
    static final double HANDLE_WEIGHT = 0.9;

    private static final Set<String> HONORIFICS = Set.of("MR", "MRS", "MS", "DR", "SHRI", "SRI", "SMT", "KUMARI");

    @Override
    public double similarity(String a, String b) {
        Alignment alignment = align(nameTokens(a), nameTokens(b));
        return alignment == null ? NO_INFO : alignment.score();
    }

    /** max(name score, {@value #HANDLE_WEIGHT} × handle score); NO_INFO only if neither gives information. */
    @Override
    public double similarityWithHandle(String payerName, String payerHandle, String customerName) {
        double name = similarity(payerName, customerName);
        double handle = handleScore(payerHandle, customerName);
        if (!NameMatcher.hasInfo(name) && !NameMatcher.hasInfo(handle)) {
            return NO_INFO;
        }
        double weightedHandle = NameMatcher.hasInfo(handle) ? HANDLE_WEIGHT * handle : NO_INFO;
        return Math.max(name, weightedHandle);
    }

    @Override
    public String evidence(String payerName, String payerHandle, String customerName) {
        double name = similarity(payerName, customerName);
        double handle = handleScore(payerHandle, customerName);
        if (NameMatcher.hasInfo(handle) && HANDLE_WEIGHT * handle > name) {
            return "UPI handle " + localPart(payerHandle) + " resembles " + customerName;
        }
        Alignment alignment = align(nameTokens(payerName), nameTokens(customerName));
        if (alignment == null) {
            return "";
        }
        return alignment.pairs().stream()
                .filter(p -> p.kind() != PairKind.EXACT)
                .map(Pair::describe)
                .collect(Collectors.joining("; "));
    }

    // ---------------------------------------------------------------- tokens

    static List<String> nameTokens(String name) {
        if (name == null) {
            return List.of();
        }
        String withoutMs = name.replaceAll("(?i)\\bM\\s*/\\s*S\\b\\.?", " "); // "M/S ACME" (messrs)
        List<String> tokens = tokens(NameNormalizer.normalize(withoutMs));
        int start = 0;
        while (start < tokens.size() && HONORIFICS.contains(tokens.get(start))) {
            start++;
        }
        return tokens.subList(start, tokens.size());
    }

    /** ravi.k@okhdfcbank → [RAVI, K]; digits are dropped (mobile-number handles carry no name). */
    static List<String> handleTokens(String handle) {
        String local = localPart(handle);
        if (local == null) {
            return List.of();
        }
        return tokens(local.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", " ").strip());
    }

    private static String localPart(String handle) {
        if (handle == null || handle.isBlank()) {
            return null;
        }
        int at = handle.indexOf('@');
        return (at >= 0 ? handle.substring(0, at) : handle).strip();
    }

    private static List<String> tokens(String normalized) {
        return normalized.isBlank() ? List.of() : Arrays.asList(normalized.split(" "));
    }

    /**
     * Handles are often a squashed name (lakshmicaterers, priyas), so take the better of token alignment
     * and comparing the two names with all spaces removed.
     */
    private double handleScore(String handle, String customerName) {
        List<String> handleTokens = handleTokens(handle);
        List<String> customerTokens = nameTokens(customerName);
        Alignment alignment = align(handleTokens, customerTokens);
        if (alignment == null) {
            return NO_INFO;
        }
        double joined = JaroWinkler.similarity(String.join("", handleTokens), String.join("", customerTokens));
        return Math.max(alignment.score(), joined >= MIN_TOKEN_SIMILARITY ? joined : 0.0);
    }

    // ---------------------------------------------------------------- alignment

    enum PairKind { EXACT, INITIAL, SIMILAR }

    record Pair(String left, String right, PairKind kind, double score) {
        String describe() {
            return switch (kind) {
                case EXACT -> left + " matches";
                case INITIAL -> left.length() == 1
                        ? "initial " + left + " matches " + right
                        : "initial " + right + " matches " + left;
                case SIMILAR -> left + " is close to " + right;
            };
        }
    }

    record Alignment(double score, List<Pair> pairs) {
    }

    /** Best order-independent pairing of tokens; null if either side has no tokens. */
    static Alignment align(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        Pair[][] pairs = new Pair[a.size()][b.size()];
        for (int i = 0; i < a.size(); i++) {
            for (int j = 0; j < b.size(); j++) {
                pairs[i][j] = pair(a.get(i), b.get(j));
            }
        }
        List<Pair> chosen = b.size() <= 12 ? bestAssignment(pairs, a.size(), b.size()) : greedy(pairs, a.size(), b.size());
        double total = chosen.stream().mapToDouble(Pair::score).sum();
        return new Alignment(total / Math.max(a.size(), b.size()), chosen);
    }

    private static Pair pair(String x, String y) {
        if (x.equals(y)) {
            return new Pair(x, y, PairKind.EXACT, 1.0);
        }
        boolean xInitial = x.length() == 1 && y.startsWith(x);
        boolean yInitial = y.length() == 1 && x.startsWith(y);
        if (xInitial || yInitial) {
            return new Pair(x, y, PairKind.INITIAL, INITIAL_SCORE);
        }
        double jw = JaroWinkler.similarity(x, y);
        return jw >= MIN_TOKEN_SIMILARITY ? new Pair(x, y, PairKind.SIMILAR, jw) : null;
    }

    /** Exact maximum-weight assignment by DP over subsets of b's tokens (names are short). */
    private static List<Pair> bestAssignment(Pair[][] pairs, int n, int m) {
        double[][] best = new double[n + 1][1 << m];
        int[][] choice = new int[n + 1][1 << m]; // j chosen for row i, or -1 = leave row i unaligned
        for (int i = n - 1; i >= 0; i--) {
            for (int used = 0; used < (1 << m); used++) {
                best[i][used] = best[i + 1][used];
                choice[i][used] = -1;
                for (int j = 0; j < m; j++) {
                    if (pairs[i][j] != null && (used & (1 << j)) == 0) {
                        double candidate = pairs[i][j].score() + best[i + 1][used | (1 << j)];
                        if (candidate > best[i][used] + 1e-12) {
                            best[i][used] = candidate;
                            choice[i][used] = j;
                        }
                    }
                }
            }
        }
        List<Pair> chosen = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < n; i++) {
            int j = choice[i][used];
            if (j >= 0) {
                chosen.add(pairs[i][j]);
                used |= 1 << j;
            }
        }
        return chosen;
    }

    /** Fallback for very long names: repeatedly take the best remaining pair. */
    private static List<Pair> greedy(Pair[][] pairs, int n, int m) {
        boolean[] usedA = new boolean[n];
        boolean[] usedB = new boolean[m];
        List<Pair> chosen = new ArrayList<>();
        while (true) {
            int bi = -1;
            int bj = -1;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    if (!usedA[i] && !usedB[j] && pairs[i][j] != null
                            && (bi < 0 || pairs[i][j].score() > pairs[bi][bj].score())) {
                        bi = i;
                        bj = j;
                    }
                }
            }
            if (bi < 0) {
                return chosen;
            }
            usedA[bi] = true;
            usedB[bj] = true;
            chosen.add(pairs[bi][bj]);
        }
    }
}
