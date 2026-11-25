package com.plutus.ingest.csv;

import java.time.LocalDate;

/**
 * A validated bank statement row: exactly one of credit/debit is positive.
 * A blank (or zero) credit or debit cell means "not this side".
 */
public record StatementRow(long lineNumber, LocalDate date, String narration, String ref,
                           long creditPaise, long debitPaise) {

    public boolean isCredit() {
        return creditPaise > 0;
    }

    /** @throws RowValidationException if a required cell is missing or a value is invalid */
    public static StatementRow from(CsvRow row) {
        LocalDate date = RowValues.date(row.require(TableSpec.DATE), "date");
        long credit = side(row, TableSpec.CREDIT);
        long debit = side(row, TableSpec.DEBIT);
        if (credit > 0 && debit > 0) {
            throw new RowValidationException("Row has both a credit and a debit amount");
        }
        if (credit == 0 && debit == 0) {
            throw new RowValidationException("Row has neither a credit nor a debit amount");
        }
        String narration = row.get(TableSpec.NARRATION);
        if (credit > 0 && narration == null) {
            throw new RowValidationException("Missing value for 'narration'");
        }
        return new StatementRow(row.lineNumber(), date, narration, cleanRef(row.get(TableSpec.REF)), credit, debit);
    }

    private static long side(CsvRow row, String key) {
        String value = row.get(key);
        return value == null ? 0 : RowValues.amount(value, key + " amount");
    }

    /** Banks often fill the ref column with zeros when there is no reference; treat that as absent. */
    private static String cleanRef(String ref) {
        return ref == null || ref.matches("0+") ? null : ref;
    }
}
