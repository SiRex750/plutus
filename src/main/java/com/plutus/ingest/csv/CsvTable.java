package com.plutus.ingest.csv;

import java.util.List;
import java.util.Map;

/** A parsed CSV file: where the header was, which column each key maps to, and the non-blank data rows. */
public record CsvTable(long headerLineNumber, Map<String, Integer> columns, List<CsvRow> rows) {

    public CsvTable {
        columns = Map.copyOf(columns);
        rows = List.copyOf(rows);
    }
}
