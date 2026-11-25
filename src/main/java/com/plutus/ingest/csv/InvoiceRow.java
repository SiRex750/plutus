package com.plutus.ingest.csv;

import java.time.LocalDate;

import com.plutus.common.InvoiceReference;

/** A validated invoice row. {@code invoiceNumber} is already normalised via {@link InvoiceReference}. */
public record InvoiceRow(long lineNumber, String invoiceNumber, String customerName, long amountPaise,
                         LocalDate issueDate, LocalDate dueDate) {

    static final int MAX_INVOICE_NUMBER = 64;
    static final int MAX_CUSTOMER_NAME = 200;

    /** @throws RowValidationException if a required cell is missing or a value is invalid */
    public static InvoiceRow from(CsvRow row) {
        String number = InvoiceReference.normalize(row.require(TableSpec.INVOICE_NUMBER));
        if (number.length() > MAX_INVOICE_NUMBER) {
            throw new RowValidationException("Invoice number longer than " + MAX_INVOICE_NUMBER + " characters");
        }
        String customer = row.require(TableSpec.CUSTOMER_NAME).replaceAll("\\s+", " ");
        if (customer.length() > MAX_CUSTOMER_NAME) {
            throw new RowValidationException("Customer name longer than " + MAX_CUSTOMER_NAME + " characters");
        }
        long amount = RowValues.amount(row.require(TableSpec.AMOUNT), "amount");
        if (amount <= 0) {
            throw new RowValidationException("Invoice amount must be greater than zero");
        }
        LocalDate issueDate = RowValues.date(row.require(TableSpec.ISSUE_DATE), "issue date");
        String due = row.get(TableSpec.DUE_DATE);
        LocalDate dueDate = due == null ? null : RowValues.date(due, "due date");
        return new InvoiceRow(row.lineNumber(), number, customer, amount, issueDate, dueDate);
    }
}
