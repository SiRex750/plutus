package com.plutus.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.plutus.AbstractIntegrationTest;
import com.plutus.ingest.ImportResult.RowError;

class ImportIntegrationTest extends AbstractIntegrationTest {

    private static final String INVOICES = "/samples/invoices.csv";
    private static final String STATEMENT = "/samples/bank-statement-hdfc-style.csv";

    @Autowired InvoiceImportService invoiceImport;
    @Autowired BankStatementImportService statementImport;

    @Test
    void importingInvoicesTwiceIsIdempotent() throws IOException {
        ImportResult first = importInvoices(INVOICES);
        assertThat(first).isEqualTo(new ImportResult("invoices.csv", 15, 15, 0, 0, java.util.List.of()));

        ImportResult second = importInvoices(INVOICES);
        assertThat(second.inserted()).isZero();
        assertThat(second.duplicates()).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices", Long.class)).isEqualTo(15);

        // Mixed invoice-number styles are stored normalised.
        assertThat(jdbc.queryForList("SELECT invoice_number FROM invoices ORDER BY id", String.class))
                .startsWith("INV-1001", "INV-1002", "INV-1003")
                .contains("INV-1006", "INV-1009")
                .allMatch(n -> n.matches("INV-\\d+"));
        Map<String, Object> acme = jdbc.queryForMap("SELECT * FROM invoices WHERE invoice_number = 'INV-1003'");
        assertThat(acme).containsEntry("customer_name", "Acme Traders Pvt Ltd")
                .containsEntry("amount_paise", 11_800_000L)
                .containsEntry("allocated_paise", 0L)
                .containsEntry("status", "UNPAID");
    }

    @Test
    void invoiceDuplicatedWithinFileIsInsertedOnce() {
        String csv = """
                Invoice Number,Client,Grand Total,Issue Date
                INV1002,Sharma and Sons,"45,000.00",2026-11-02
                inv-1002,Sharma and Sons,"45,000.00",2026-11-02
                INV-2000,Bad Amount Co,abc,2026-11-02
                """;
        ImportResult result = invoiceImport.importCsv(stream(csv), "dup.csv");

        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.errors()).extracting(RowError::lineNumber).containsExactly(4L);
    }

    @Test
    void importsBankStatementWithCorrectCounts() throws IOException {
        ImportResult result = importStatement();

        assertThat(result.totalRows()).isEqualTo(25);
        assertThat(result.inserted()).isEqualTo(16);
        assertThat(result.duplicates()).isEqualTo(1); // the repeated Krishna & Co. line
        assertThat(result.skipped()).isEqualTo(7);    // debits
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).lineNumber()).isEqualTo(22);
        assertThat(result.errors().get(0).message()).contains("6,OOO.00");
        assertThat(result.inserted() + result.duplicates() + result.skipped() + result.errors().size())
                .isEqualTo(result.totalRows());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isEqualTo(16);
    }

    @Test
    void spotChecksParsedPaymentFields() throws IOException {
        importStatement();

        Map<String, Object> ravi = payment("0000412345678901");
        assertThat(ravi).containsEntry("source", "UPI")
                .containsEntry("payer_name", "RAVI KUMAR")
                .containsEntry("payer_handle", "ravi@okaxis")
                .containsEntry("reference_hint", "INV-1001")
                .containsEntry("amount_paise", 1_250_000L)
                .containsEntry("status", "UNMATCHED")
                .containsEntry("narration", "UPI/CR/412345678901/RAVI KUMAR/SBIN/ravi@okaxis/INV-1001");
        // 03/11/26 at start of day in Asia/Kolkata.
        assertThat(((OffsetDateTime) jdbc.queryForObject(
                "SELECT received_at FROM payments WHERE external_ref = '0000412345678901'", OffsetDateTime.class))
                .toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 11, 2, 18, 30, 0, 0, ZoneOffset.UTC).toInstant());

        Map<String, Object> acme = payment("HDFCN52026110512345");
        assertThat(acme).containsEntry("source", "BANK_TRANSFER")
                .containsEntry("payer_name", "ACME TRADERS PVT LTD")
                .containsEntry("reference_hint", "INV-1003")
                .containsEntry("amount_paise", 11_800_000L);
        assertThat((String) acme.get("narration")).contains("inv 1003, thanks");

        Map<String, Object> rKumar = payment("0000412345678904");
        assertThat(rKumar).containsEntry("payer_name", "R. KUMAR").containsEntry("reference_hint", null);

        Map<String, Object> priya = payment("0000412345678903");
        assertThat(priya).containsEntry("payer_name", null).containsEntry("payer_handle", "priya.sharma@oksbi");

        // No usable reference anywhere -> deterministic hash fallback.
        Map<String, Object> cash = jdbc.queryForMap("SELECT * FROM payments WHERE narration = 'CASH DEPOSIT BY SELF'");
        assertThat((String) cash.get("external_ref")).startsWith("H:").hasSize(34);
        assertThat(cash).containsEntry("source", "BANK_TRANSFER").containsEntry("payer_name", null);
        Map<String, Object> interest = jdbc.queryForMap(
                "SELECT * FROM payments WHERE narration = 'CREDIT INTEREST CAPITALISED'");
        assertThat((String) interest.get("external_ref")).startsWith("H:");
    }

    @Test
    void reuploadingStatementInsertsNothing() throws IOException {
        importStatement();
        ImportResult again = importStatement();

        assertThat(again.inserted()).isZero();
        assertThat(again.duplicates()).isEqualTo(17); // 16 already stored + the in-file repeat
        assertThat(again.skipped()).isEqualTo(7);
        assertThat(again.errors()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isEqualTo(16);
    }

    private Map<String, Object> payment(String externalRef) {
        return jdbc.queryForMap("SELECT * FROM payments WHERE external_ref = ?", externalRef);
    }

    private ImportResult importInvoices(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return invoiceImport.importCsv(in, "invoices.csv");
        }
    }

    private ImportResult importStatement() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(STATEMENT)) {
            return statementImport.importCsv(in, "bank-statement-hdfc-style.csv");
        }
    }

    private static InputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
