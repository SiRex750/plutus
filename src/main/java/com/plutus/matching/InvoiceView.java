package com.plutus.matching;

import java.time.LocalDate;
import java.util.Objects;

/** What the matcher needs to know about an open invoice. {@code invoiceNumber} is already normalised. */
public record InvoiceView(long id, String invoiceNumber, String customerName, long outstandingPaise,
                          LocalDate issueDate, LocalDate dueDate) {

    public InvoiceView {
        Objects.requireNonNull(invoiceNumber, "invoiceNumber");
        Objects.requireNonNull(issueDate, "issueDate");
    }
}
