package com.plutus.ingest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.plutus.ingest.ImportResult.RowError;
import com.plutus.ingest.csv.CsvRow;
import com.plutus.ingest.csv.CsvTable;
import com.plutus.ingest.csv.CsvTableReader;
import com.plutus.ingest.csv.RowValidationException;
import com.plutus.ingest.csv.StatementRow;
import com.plutus.ingest.csv.TableSpec;

/**
 * Imports a bank / UPI statement CSV. Only credits become payments; debits are counted as skipped.
 * Idempotent on (source, external_ref): re-uploading the same statement inserts nothing.
 */
@Service
public class BankStatementImportService {

    // Race-safe idempotency: the unique constraint decides, not a prior SELECT.
    private static final String INSERT_SQL = """
            INSERT INTO payments (source, external_ref, payer_name, payer_handle, amount_paise, allocated_paise,
                                  received_at, narration, reference_hint, status)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, 'UNMATCHED')
            ON CONFLICT (source, external_ref) DO NOTHING""";

    static final int MAX_EXTERNAL_REF = 100;
    private static final int MAX_PAYER = 200;
    private static final int MAX_HINT = 100;

    private final JdbcTemplate jdbc;
    private final NarrationParser narrationParser;
    private final Clock clock;

    public BankStatementImportService(JdbcTemplate jdbc, NarrationParser narrationParser, Clock clock) {
        this.jdbc = jdbc;
        this.narrationParser = narrationParser;
        this.clock = clock;
    }

    @Transactional
    public ImportResult importCsv(InputStream in, String fileName) {
        CsvTable table = CsvTableReader.read(in, TableSpec.BANK_STATEMENT);

        List<RowError> errors = new ArrayList<>();
        Map<String, Object[]> unique = new LinkedHashMap<>();
        int duplicates = 0;
        int skipped = 0;
        for (CsvRow csvRow : table.rows()) {
            try {
                StatementRow row = StatementRow.from(csvRow);
                if (!row.isCredit()) {
                    skipped++;
                    continue;
                }
                ParsedNarration parsed = narrationParser.parse(row.narration());
                String externalRef = externalRef(row, parsed);
                if (externalRef.length() > MAX_EXTERNAL_REF) {
                    throw new RowValidationException("Reference longer than " + MAX_EXTERNAL_REF + " characters");
                }
                Object[] args = {
                        parsed.source().name(), externalRef,
                        truncate(parsed.payerName(), MAX_PAYER), truncate(parsed.payerHandle(), MAX_PAYER),
                        row.creditPaise(), receivedAt(row), row.narration(),
                        truncate(parsed.referenceHint(), MAX_HINT)};
                if (unique.putIfAbsent(parsed.source() + "|" + externalRef, args) != null) {
                    duplicates++; // repeated within this file
                }
            } catch (RowValidationException e) {
                errors.add(new RowError(csvRow.lineNumber(), e.getMessage()));
            }
        }

        List<Object[]> args = List.copyOf(unique.values());
        int inserted = args.isEmpty() ? 0 : BatchCounts.inserted(jdbc.batchUpdate(INSERT_SQL, args));
        duplicates += args.size() - inserted;

        return new ImportResult(fileName, table.rows().size(), inserted, duplicates, skipped, errors);
    }

    /** Ref column, else the parsed UTR/RRN, else a deterministic hash so re-uploads stay idempotent. */
    static String externalRef(StatementRow row, ParsedNarration parsed) {
        if (row.ref() != null) {
            return row.ref();
        }
        if (parsed.transactionRef() != null) {
            return parsed.transactionRef();
        }
        return "H:" + sha256Hex(row.date() + "|" + row.creditPaise() + "|" + row.narration()).substring(0, 32);
    }

    /** Statement lines carry only a date: use the start of that day in the merchant's zone (the clock's zone). */
    private OffsetDateTime receivedAt(StatementRow row) {
        Instant instant = row.date().atStartOfDay(clock.getZone()).toInstant();
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
