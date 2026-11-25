package com.plutus.ingest.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.plutus.ingest.csv.CsvImportException.Reason;

class CsvTableReaderTest {

    @Test
    void detectsHeaderAfterBankPreamble() throws Exception {
        CsvTable table;
        try (InputStream in = getClass().getResourceAsStream("/samples/bank-statement-hdfc-style.csv")) {
            table = CsvTableReader.read(in, TableSpec.BANK_STATEMENT);
        }

        assertThat(table.headerLineNumber()).isEqualTo(5);
        assertThat(table.columns()).containsEntry(TableSpec.DATE, 0)
                .containsEntry(TableSpec.NARRATION, 1)
                .containsEntry(TableSpec.REF, 2)
                .containsEntry(TableSpec.DEBIT, 4)
                .containsEntry(TableSpec.CREDIT, 5);
        assertThat(table.rows()).hasSize(25);
        assertThat(table.rows().get(0).lineNumber()).isEqualTo(6);
        // Quoted narration with a comma stays one cell.
        assertThat(table.rows().get(3).get(TableSpec.NARRATION)).contains("inv 1003, thanks");
    }

    @ParameterizedTest(name = "\"{0}\" is {1}")
    @CsvSource(delimiter = '|', value = {
            "Chq./Ref.No.      | ref",
            "chq/ref no        | ref",
            "CHQ REF NO        | ref",
            "UTR               | ref",
            "Deposit Amt.      | credit",
            "deposit amt       | credit",
            "Credit Amount     | credit",
            "Withdrawal Amt.   | debit",
            "Txn  Date         | date",
            "Value Date        | date",
            "PARTICULARS       | narration",
            "Remarks           | narration",
    })
    void matchesStatementAliasesIgnoringCaseSpacesAndPunctuation(String header, String key) {
        assertThat(TableSpec.BANK_STATEMENT.column(key).matches(header)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" is {1}")
    @CsvSource(delimiter = '|', value = {
            "Invoice #     | invoice_number",
            "INV. NO.      | invoice_number",
            "invoice_number| invoice_number",
            "Bill To       | customer_name",
            "client        | customer_name",
            "Grand Total   | amount",
            "Invoice Date  | issue_date",
            "Due           | due_date",
    })
    void matchesInvoiceAliases(String header, String key) {
        assertThat(TableSpec.INVOICES.column(key).matches(header)).isTrue();
    }

    @Test
    void nonAliasesDoNotMatch() {
        assertThat(TableSpec.BANK_STATEMENT.column(TableSpec.DATE).matches("Value Dt")).isFalse();
        assertThat(TableSpec.BANK_STATEMENT.column(TableSpec.CREDIT).matches("Closing Balance")).isFalse();
        assertThat(TableSpec.INVOICES.column(TableSpec.ISSUE_DATE).matches("Due Date")).isFalse();
    }

    @Test
    void dropsBlankRowsAndKeepsRealLineNumbers() {
        CsvTable table = read("""
                Txn Date,Description,Credit
                25/11/2026,UPI/1,100

                ,,
                26/11/2026,"multi
                line",200
                27/11/2026,UPI/3,300
                """, TableSpec.BANK_STATEMENT);

        assertThat(table.rows()).extracting(CsvRow::lineNumber).containsExactly(2L, 5L, 7L);
    }

    @Test
    void headerBeyondThirtyRowsIsRejected() {
        String preamble = "junk line\n".repeat(30);
        assertThatThrownBy(() -> read(preamble + "Date,Narration,Credit\n25/11/2026,x,1\n", TableSpec.BANK_STATEMENT))
                .isInstanceOf(CsvImportException.class)
                .extracting(e -> ((CsvImportException) e).reason()).isEqualTo(Reason.HEADER_NOT_FOUND);

        String shorter = "junk line\n".repeat(29);
        assertThat(read(shorter + "Date,Narration,Credit\n25/11/2026,x,1\n", TableSpec.BANK_STATEMENT).rows()).hasSize(1);
    }

    @Test
    void missingRequiredColumnRejectsFile() {
        assertThatThrownBy(() -> read("Date,Narration,Withdrawal Amt\n25/11/2026,x,1\n", TableSpec.BANK_STATEMENT))
                .isInstanceOf(CsvImportException.class)
                .hasMessageContaining("credit")
                .extracting(e -> ((CsvImportException) e).reason()).isEqualTo(Reason.MISSING_COLUMNS);
    }

    @Test
    void emptyAndUnreadableFilesAreRejected() {
        assertReason("", Reason.EMPTY_FILE);
        assertReason("   \n\n", Reason.EMPTY_FILE);
        assertReason(",,,\n,,\n", Reason.EMPTY_FILE);
        assertReason("Date,Narration,Credit\n25/11/2026,\"unterminated,1\n", Reason.UNREADABLE);
        assertReason("hello,world\n1,2\n", Reason.HEADER_NOT_FOUND);
    }

    @Test
    void stripsByteOrderMark() {
        CsvTable table = read("﻿Invoice No,Customer,Amount,Date\nINV-1,A,10,25/11/2026\n", TableSpec.INVOICES);
        assertThat(table.rows()).hasSize(1);
    }

    private static void assertReason(String csv, Reason reason) {
        assertThatThrownBy(() -> read(csv, TableSpec.BANK_STATEMENT))
                .isInstanceOf(CsvImportException.class)
                .extracting(e -> ((CsvImportException) e).reason()).isEqualTo(reason);
    }

    private static CsvTable read(String csv, TableSpec spec) {
        return CsvTableReader.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), spec);
    }
}
