package com.plutus.ingest.csv;

import java.util.List;
import java.util.Map;

/** One data row, with cells addressable by logical column key. */
public record CsvRow(long lineNumber, List<String> cells, Map<String, Integer> columns) {

    public CsvRow {
        cells = List.copyOf(cells);
        columns = Map.copyOf(columns);
    }

    /** The stripped cell value, or null if the column is absent, the row is short, or the cell is blank. */
    public String get(String key) {
        Integer index = columns.get(key);
        if (index == null || index >= cells.size()) {
            return null;
        }
        String value = cells.get(index);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Like {@link #get} but a missing value is a row error. */
    public String require(String key) {
        String value = get(key);
        if (value == null) {
            throw new RowValidationException("Missing value for '" + key + "'");
        }
        return value;
    }
}
