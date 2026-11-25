package com.plutus.ingest;

import java.sql.Statement;

/** Interprets JDBC batch update counts from {@code INSERT ... ON CONFLICT DO NOTHING}: 1 = inserted, 0 = conflict. */
final class BatchCounts {

    private BatchCounts() {
    }

    static int inserted(int[] counts) {
        int inserted = 0;
        for (int count : counts) {
            if (count == Statement.SUCCESS_NO_INFO || count < 0) {
                // Without per-row counts we cannot tell inserts from duplicates; fail loudly rather than guess.
                throw new IllegalStateException("JDBC driver did not report per-row update counts");
            }
            inserted += count;
        }
        return inserted;
    }
}
