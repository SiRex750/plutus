package com.plutus.matching;

/**
 * Compares a payer with a customer name.
 *
 * <p><b>"No information" vs "different":</b> a score is 0..1 (1 = same party, 0 = clearly different), or
 * exactly {@link #NO_INFO} when either side has no usable name (null, blank, or nothing left after
 * normalisation). Callers must test {@code score == NO_INFO} before applying thresholds. NO_INFO is
 * negative on purpose: if a caller forgets, it still compares below every threshold and can never
 * produce a match by accident.
 */
public interface NameMatcher {

    double NO_INFO = -1.0;

    /** Similarity of two names, 0..1, or {@link #NO_INFO}. Must be null-safe. */
    double similarity(String a, String b);

    /**
     * Similarity using both the payer's name and their UPI handle (e.g. ravi.k@okhdfcbank).
     * Default: the handle is ignored.
     */
    default double similarityWithHandle(String payerName, String payerHandle, String customerName) {
        return similarity(payerName, customerName);
    }

    /**
     * Plain-English evidence behind the score, for reasons shown to the merchant, e.g.
     * "initial R matches RAVI". Empty when there is nothing worth saying (e.g. an exact match).
     */
    default String evidence(String payerName, String payerHandle, String customerName) {
        return "";
    }

    static boolean hasInfo(double score) {
        return score != NO_INFO;
    }
}
