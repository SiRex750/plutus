package com.plutus.ingest.csv;

/** A problem with the file as a whole (not a single row); the entire upload is rejected. */
public class CsvImportException extends RuntimeException {

    public enum Reason {
        EMPTY_FILE, UNREADABLE, HEADER_NOT_FOUND, MISSING_COLUMNS
    }

    private final Reason reason;

    public CsvImportException(Reason reason, String message) {
        this(reason, message, null);
    }

    public CsvImportException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
