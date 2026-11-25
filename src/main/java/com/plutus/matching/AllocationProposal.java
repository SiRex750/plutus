package com.plutus.matching;

/** "Put this much of the payment against this invoice." */
public record AllocationProposal(long invoiceId, long amountPaise) {

    public AllocationProposal {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Allocation amount must be positive: " + amountPaise);
        }
    }
}
