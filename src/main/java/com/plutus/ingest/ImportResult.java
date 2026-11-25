package com.plutus.ingest;

import java.util.List;

/**
 * Outcome of one CSV upload. Every data row is counted exactly once:
 * {@code totalRows == inserted + duplicates + skipped + errors.size()}.
 *
 * @param duplicates rows already in the database, or repeated earlier in the same file
 * @param skipped    rows deliberately not imported (e.g. debits in a bank statement)
 */
public record ImportResult(String fileName, int totalRows, int inserted, int duplicates, int skipped,
                           List<RowError> errors) {

    public ImportResult {
        errors = List.copyOf(errors);
    }

    /** A data row that could not be imported; {@code lineNumber} is the 1-based line in the file. */
    public record RowError(long lineNumber, String message) {
    }
}
