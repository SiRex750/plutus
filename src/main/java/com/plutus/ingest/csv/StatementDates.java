package com.plutus.ingest.csv;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;

/**
 * Parses the date formats found in Indian invoices and bank exports. Day always comes before month
 * (Indian convention); parsing is strict, so 31/02/2026 or 2026-13-01 are rejected.
 * Accepted: yyyy-MM-dd, dd-MM-yyyy, dd/MM/yyyy, dd/MM/yy (20yy), dd-MMM-yyyy, dd MMM yyyy.
 */
public final class StatementDates {

    private static final List<DateTimeFormatter> FORMATS = List.of(
            strict("uuuu-MM-dd"),
            strict("dd-MM-uuuu"),
            strict("dd/MM/uuuu"),
            new DateTimeFormatterBuilder()
                    .appendPattern("dd/MM/")
                    .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                    .toFormatter(Locale.ENGLISH)
                    .withResolverStyle(ResolverStyle.STRICT),
            strict("dd-MMM-uuuu"),
            strict("dd MMM uuuu"));

    private StatementDates() {
    }

    public static LocalDate parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Date is empty");
        }
        String trimmed = value.strip();
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException("Unrecognised date: '" + value + "'");
    }

    private static DateTimeFormatter strict(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }
}
