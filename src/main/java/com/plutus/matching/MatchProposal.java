package com.plutus.matching;

import java.util.List;
import java.util.Objects;

import com.plutus.domain.MatchType;

/**
 * A proposed match for one payment, with a plain-English {@code reason} suitable for the review queue.
 * The allocations may total less than the payment (e.g. an overpayment leaves the excess unallocated).
 */
public record MatchProposal(long paymentId, List<AllocationProposal> allocations, MatchType type, double confidence,
                            Decision decision, String reason) {

    public MatchProposal {
        allocations = List.copyOf(allocations);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reason, "reason");
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("A proposal needs at least one allocation");
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Confidence must be within 0..1: " + confidence);
        }
    }

    public long allocatedPaise() {
        return allocations.stream().mapToLong(AllocationProposal::amountPaise).sum();
    }
}
