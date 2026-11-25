package com.plutus.ingest.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StatementDatesTest {

    private static final LocalDate NOV_25 = LocalDate.of(2026, 11, 25);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "2026-11-25",   // yyyy-MM-dd
            "25-11-2026",   // dd-MM-yyyy
            "25/11/2026",   // dd/MM/yyyy
            "25/11/26",     // dd/MM/yy
            "25-Nov-2026",  // dd-MMM-yyyy
            "25-NOV-2026",
            "25-nov-2026",
            "25 Nov 2026",  // dd MMM yyyy
            "  25/11/2026 ",
    })
    void parsesEverySupportedFormat(String input) {
        assertThat(StatementDates.parse(input)).isEqualTo(NOV_25);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"05/01/26", "05-01-2026", "05 Jan 2026"})
    void dayComesBeforeMonth(String input) {
        assertThat(StatementDates.parse(input)).isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {
            "31/02/2026",   // no such day
            "2026-13-01",   // no such month
            "11/25/2026",   // US order
            "5/11/2026",    // single-digit day is not an accepted format
            "25/11/226",
            "25.11.2026",
            "Nov 25, 2026",
            "yesterday",
            "25-Foo-2026",
            "2026/11/25",
    })
    void rejectsGarbage(String input) {
        assertThatThrownBy(() -> StatementDates.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }
}
