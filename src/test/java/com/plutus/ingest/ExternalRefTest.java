package com.plutus.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.plutus.domain.PaymentSource;
import com.plutus.ingest.csv.StatementRow;

class ExternalRefTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 25);

    private static ParsedNarration parsed(String ref) {
        return new ParsedNarration(PaymentSource.UPI, ref, null, null, null);
    }

    private static StatementRow row(String ref, long credit, String narration) {
        return new StatementRow(1, DAY, narration, ref, credit, 0);
    }

    @Test
    void refColumnWinsOverParsedRef() {
        assertThat(BankStatementImportService.externalRef(row("COLREF", 100, "x"), parsed("412345678901")))
                .isEqualTo("COLREF");
    }

    @Test
    void parsedRefIsUsedWhenColumnBlank() {
        assertThat(BankStatementImportService.externalRef(row(null, 100, "x"), parsed("412345678901")))
                .isEqualTo("412345678901");
    }

    @Test
    void hashFallbackIsDeterministicAndSensitiveToEachInput() {
        String a = BankStatementImportService.externalRef(row(null, 500_000, "CASH DEPOSIT"), parsed(null));
        String same = BankStatementImportService.externalRef(row(null, 500_000, "CASH DEPOSIT"), parsed(null));

        assertThat(a).startsWith("H:").hasSize(34).matches("H:[0-9a-f]{32}").isEqualTo(same);
        assertThat(BankStatementImportService.externalRef(row(null, 500_001, "CASH DEPOSIT"), parsed(null)))
                .isNotEqualTo(a);
        assertThat(BankStatementImportService.externalRef(row(null, 500_000, "CASH DEPOSIT 2"), parsed(null)))
                .isNotEqualTo(a);
        assertThat(BankStatementImportService.externalRef(
                new StatementRow(1, DAY.plusDays(1), "CASH DEPOSIT", null, 500_000, 0), parsed(null)))
                .isNotEqualTo(a);
    }
}
