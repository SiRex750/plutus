package com.plutus.ingest.csv;

import java.time.LocalDate;

import com.plutus.common.Money;

/** Converts cell text to typed values, turning parse failures into row errors. */
final class RowValues {

    private RowValues() {
    }

    static long amount(String value, String label) {
        try {
            return Money.parseRupeesToPaise(value);
        } catch (IllegalArgumentException e) {
            throw new RowValidationException("Invalid " + label + ": '" + value + "'");
        }
    }

    static LocalDate date(String value, String label) {
        try {
            return StatementDates.parse(value);
        } catch (IllegalArgumentException e) {
            throw new RowValidationException("Invalid " + label + ": '" + value + "'");
        }
    }
}
