package com.plutus.ingest.csv;

/** A problem with one data row; the row is reported and the rest of the file still imports. */
public class RowValidationException extends RuntimeException {

    public RowValidationException(String message) {
        super(message);
    }
}
