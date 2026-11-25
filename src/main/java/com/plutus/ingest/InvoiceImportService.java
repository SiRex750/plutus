package com.plutus.ingest;

import java.io.InputStream;
import java.util.ArrayList;
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
import com.plutus.ingest.csv.InvoiceRow;
import com.plutus.ingest.csv.RowValidationException;
import com.plutus.ingest.csv.TableSpec;

/**
 * Imports an invoices CSV. Idempotent: re-uploading reports every row as a duplicate.
 * Rows with problems are reported and skipped; the rest of the file still imports.
 */
@Service
public class InvoiceImportService {

    // Race-safe idempotency: the unique constraint decides, not a prior SELECT.
    private static final String INSERT_SQL = """
            INSERT INTO invoices (invoice_number, customer_name, amount_paise, allocated_paise, status,
                                  issue_date, due_date)
            VALUES (?, ?, ?, 0, 'UNPAID', ?, ?)
            ON CONFLICT (invoice_number) DO NOTHING""";

    private final JdbcTemplate jdbc;

    public InvoiceImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ImportResult importCsv(InputStream in, String fileName) {
        CsvTable table = CsvTableReader.read(in, TableSpec.INVOICES);

        List<RowError> errors = new ArrayList<>();
        Map<String, InvoiceRow> unique = new LinkedHashMap<>();
        int duplicates = 0;
        for (CsvRow csvRow : table.rows()) {
            try {
                InvoiceRow row = InvoiceRow.from(csvRow);
                if (unique.putIfAbsent(row.invoiceNumber(), row) != null) {
                    duplicates++; // repeated within this file
                }
            } catch (RowValidationException e) {
                errors.add(new RowError(csvRow.lineNumber(), e.getMessage()));
            }
        }

        List<Object[]> args = unique.values().stream()
                .map(r -> new Object[] {r.invoiceNumber(), r.customerName(), r.amountPaise(), r.issueDate(), r.dueDate()})
                .toList();
        int inserted = args.isEmpty() ? 0 : BatchCounts.inserted(jdbc.batchUpdate(INSERT_SQL, args));
        duplicates += args.size() - inserted;

        return new ImportResult(fileName, table.rows().size(), inserted, duplicates, 0, errors);
    }
}
