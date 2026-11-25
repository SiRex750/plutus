package com.plutus.ingest.csv;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.plutus.ingest.csv.CsvImportException.Reason;

/**
 * Reads a CSV file into a {@link CsvTable}: finds the header row (skipping bank-export preamble such as
 * account name, address and period), maps columns by alias, and returns the non-blank rows after it.
 * Plain Java, no Spring. File-level problems throw {@link CsvImportException}.
 */
public final class CsvTableReader {

    public static final int MAX_HEADER_SEARCH_ROWS = 30;

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setIgnoreEmptyLines(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .get();

    private CsvTableReader() {
    }

    public static CsvTable read(InputStream in, TableSpec spec) {
        String content;
        try {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CsvImportException(Reason.UNREADABLE, "Could not read the uploaded file", e);
        }
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        if (content.isBlank()) {
            throw new CsvImportException(Reason.EMPTY_FILE, "The uploaded file is empty");
        }

        List<CSVRecord> records;
        try (CSVParser parser = CSVParser.builder().setReader(new StringReader(content)).setFormat(FORMAT).get()) {
            records = parser.getRecords();
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            throw new CsvImportException(Reason.UNREADABLE, "The file is not readable as CSV: " + e.getMessage(), e);
        }
        if (records.stream().allMatch(CsvTableReader::isBlank)) {
            throw new CsvImportException(Reason.EMPTY_FILE, "The uploaded file has no rows");
        }

        int headerIndex = findHeader(records, spec);
        List<String> header = records.get(headerIndex).toList();
        Map<String, Integer> columns = mapColumns(header, spec);

        LineIndex lines = new LineIndex(content);
        List<CsvRow> rows = new ArrayList<>();
        for (CSVRecord record : records.subList(headerIndex + 1, records.size())) {
            if (!isBlank(record)) {
                rows.add(new CsvRow(lines.lineOf(record.getCharacterPosition()), record.toList(), columns));
            }
        }
        return new CsvTable(lines.lineOf(records.get(headerIndex).getCharacterPosition()), columns, rows);
    }

    private static int findHeader(List<CSVRecord> records, TableSpec spec) {
        int limit = Math.min(MAX_HEADER_SEARCH_ROWS, records.size());
        for (int i = 0; i < limit; i++) {
            List<String> cells = records.get(i).toList();
            boolean allPresent = spec.detectionKeys().stream()
                    .allMatch(key -> spec.column(key).indexIn(cells) >= 0);
            if (allPresent) {
                return i;
            }
        }
        String expected = spec.detectionKeys().stream()
                .map(key -> key + " (" + String.join(" / ", spec.column(key).aliases()) + ")")
                .collect(Collectors.joining(" and "));
        throw new CsvImportException(Reason.HEADER_NOT_FOUND, "No " + spec.name() + " header row found in the first "
                + MAX_HEADER_SEARCH_ROWS + " rows; expected a row with columns " + expected);
    }

    private static Map<String, Integer> mapColumns(List<String> header, TableSpec spec) {
        Map<String, Integer> columns = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (ColumnSpec column : spec.columns()) {
            int index = column.indexIn(header);
            if (index >= 0) {
                columns.put(column.key(), index);
            } else if (column.required()) {
                missing.add(column.key() + " (" + String.join(" / ", column.aliases()) + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new CsvImportException(Reason.MISSING_COLUMNS,
                    "The " + spec.name() + " header is missing required column(s): " + String.join(", ", missing));
        }
        return columns;
    }

    private static boolean isBlank(CSVRecord record) {
        return record.stream().allMatch(cell -> cell == null || cell.isBlank());
    }

    /** Maps character offsets to 1-based line numbers (correct even with quoted multi-line cells). */
    private static final class LineIndex {
        private final int[] lineStarts;

        LineIndex(String content) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        long lineOf(long offset) {
            int idx = Arrays.binarySearch(lineStarts, (int) offset);
            return (idx >= 0 ? idx : -idx - 2) + 1;
        }
    }
}
