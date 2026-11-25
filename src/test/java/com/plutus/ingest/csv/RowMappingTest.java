package com.plutus.ingest.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RowMappingTest {

    private static final Map<String, Integer> STATEMENT_COLUMNS = Map.of(
            TableSpec.DATE, 0, TableSpec.NARRATION, 1, TableSpec.REF, 2, TableSpec.DEBIT, 3, TableSpec.CREDIT, 4);

    private static StatementRow statement(String... cells) {
        return StatementRow.from(new CsvRow(9, Arrays.asList(cells), STATEMENT_COLUMNS));
    }

    @Test
    void blankDebitMeansCredit() {
        StatementRow row = statement("25/11/2026", "UPI/x", "UTR1", "", "1,250.50");
        assertThat(row.isCredit()).isTrue();
        assertThat(row.creditPaise()).isEqualTo(125_050);
        assertThat(row.debitPaise()).isZero();
        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    void blankCreditMeansDebit() {
        StatementRow row = statement("25/11/2026", "ATM", "", "500.00", "  ");
        assertThat(row.isCredit()).isFalse();
        assertThat(row.debitPaise()).isEqualTo(50_000);
    }

    @Test
    void zeroIsTreatedLikeBlank() {
        assertThat(statement("25/11/2026", "UPI/x", "", "0.00", "10").isCredit()).isTrue();
    }

    @Test
    void shortRowWithMissingTrailingCellsStillReadsDebit() {
        StatementRow row = StatementRow.from(new CsvRow(1, Arrays.asList("25/11/2026", "ATM", "", "500"),
                STATEMENT_COLUMNS));
        assertThat(row.isCredit()).isFalse();
        assertThat(row.debitPaise()).isEqualTo(50_000);
    }

    @Test
    void neitherOrBothSidesIsARowError() {
        assertThatThrownBy(() -> statement("25/11/2026", "x", "", "", ""))
                .isInstanceOf(RowValidationException.class).hasMessageContaining("neither");
        assertThatThrownBy(() -> statement("25/11/2026", "x", "", "1", "2"))
                .isInstanceOf(RowValidationException.class).hasMessageContaining("both");
    }

    @Test
    void badCellsAreRowErrors() {
        assertThatThrownBy(() -> statement("25/11/2026", "x", "", "", "12,5O0.00"))
                .isInstanceOf(RowValidationException.class).hasMessageContaining("credit");
        assertThatThrownBy(() -> statement("2026/25/11", "x", "", "", "10"))
                .isInstanceOf(RowValidationException.class).hasMessageContaining("date");
        assertThatThrownBy(() -> statement("", "x", "", "", "10"))
                .isInstanceOf(RowValidationException.class).hasMessageContaining("date");
        assertThatThrownBy(() -> statement("25/11/2026", "", "", "", "10"))
                .isInstanceOf(RowValidationException.class).hasMessageContaining("narration");
    }

    @Test
    void allZeroRefIsTreatedAsMissing() {
        assertThat(statement("25/11/2026", "x", "0000000000000000", "", "10").ref()).isNull();
        assertThat(statement("25/11/2026", "x", "0000412345678901", "", "10").ref()).isEqualTo("0000412345678901");
    }

    @Test
    void invoiceRowNormalisesNumberAndValidates() {
        Map<String, Integer> cols = Map.of(TableSpec.INVOICE_NUMBER, 0, TableSpec.CUSTOMER_NAME, 1,
                TableSpec.AMOUNT, 2, TableSpec.ISSUE_DATE, 3, TableSpec.DUE_DATE, 4);

        InvoiceRow row = InvoiceRow.from(new CsvRow(2,
                Arrays.asList("inv 1003", "Acme  Traders", "₹1,18,000.00", "03/11/2026", ""), cols));
        assertThat(row.invoiceNumber()).isEqualTo("INV-1003");
        assertThat(row.customerName()).isEqualTo("Acme Traders");
        assertThat(row.amountPaise()).isEqualTo(11_800_000);
        assertThat(row.dueDate()).isNull();

        assertThatThrownBy(() -> InvoiceRow.from(new CsvRow(3, Arrays.asList("INV-1", "A", "0", "03/11/2026"), cols)))
                .isInstanceOf(RowValidationException.class).hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> InvoiceRow.from(new CsvRow(4, Arrays.asList("", "A", "10", "03/11/2026"), cols)))
                .isInstanceOf(RowValidationException.class).hasMessageContaining("invoice_number");
        assertThatThrownBy(() -> InvoiceRow.from(new CsvRow(5, Arrays.asList("INV-1", "A", "10", "03/11/2026", "soon"), cols)))
                .isInstanceOf(RowValidationException.class).hasMessageContaining("due date");
    }
}
