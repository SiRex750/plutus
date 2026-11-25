package com.plutus.matching;

import java.time.LocalDate;
import java.util.Objects;

import com.plutus.domain.PaymentSource;

/** What the matcher needs to know about a payment. {@code referenceHint} is already normalised (e.g. "INV-1042"). */
public record PaymentView(long id, long unallocatedPaise, String payerName, String payerHandle, String referenceHint,
                          LocalDate receivedDate, PaymentSource source) {

    public PaymentView {
        Objects.requireNonNull(receivedDate, "receivedDate");
        Objects.requireNonNull(source, "source");
    }
}
