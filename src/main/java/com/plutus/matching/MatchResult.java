package com.plutus.matching;

import java.util.List;

/** Proposals in payment processing order, plus the payments nothing could be proposed for. */
public record MatchResult(List<MatchProposal> proposals, List<Long> unmatchedPaymentIds) {

    public MatchResult {
        proposals = List.copyOf(proposals);
        unmatchedPaymentIds = List.copyOf(unmatchedPaymentIds);
    }
}
