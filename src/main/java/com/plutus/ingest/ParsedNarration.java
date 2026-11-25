package com.plutus.ingest;

import com.plutus.domain.PaymentSource;

/**
 * Structured fields extracted from a bank/UPI statement narration.
 * Any field other than {@code source} may be null when it could not be found with confidence.
 *
 * @param source         UPI, or BANK_TRANSFER for NEFT/RTGS/IMPS and anything unrecognised
 * @param transactionRef UTR / RRN / NEFT-RTGS reference
 * @param payerName      trimmed, single-spaced, uppercase
 * @param payerHandle    UPI VPA, lowercase
 * @param referenceHint  invoice reference, normalised (e.g. "INV-1042")
 */
public record ParsedNarration(
        PaymentSource source,
        String transactionRef,
        String payerName,
        String payerHandle,
        String referenceHint) {

    public static ParsedNarration empty() {
        return new ParsedNarration(PaymentSource.BANK_TRANSFER, null, null, null, null);
    }
}
