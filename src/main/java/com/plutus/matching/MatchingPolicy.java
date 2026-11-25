package com.plutus.matching;

import java.util.Optional;

/**
 * Confidence thresholds: at or above {@code autoConfirmThreshold} a match is applied automatically,
 * at or above {@code suggestThreshold} it goes to the review queue, below that nothing is proposed.
 */
public record MatchingPolicy(double autoConfirmThreshold, double suggestThreshold) {

    public static final MatchingPolicy DEFAULT = new MatchingPolicy(0.90, 0.50);

    public MatchingPolicy {
        if (suggestThreshold < 0 || autoConfirmThreshold > 1 || suggestThreshold > autoConfirmThreshold) {
            throw new IllegalArgumentException(
                    "Need 0 <= suggest <= autoConfirm <= 1, got " + suggestThreshold + " / " + autoConfirmThreshold);
        }
    }

    /** The decision for a confidence, or empty if it is too low to propose at all. */
    public Optional<Decision> decide(double confidence) {
        if (confidence >= autoConfirmThreshold) {
            return Optional.of(Decision.AUTO_CONFIRM);
        }
        if (confidence >= suggestThreshold) {
            return Optional.of(Decision.SUGGEST);
        }
        return Optional.empty();
    }
}
