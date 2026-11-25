package com.plutus.ingest.csv;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A logical column and the header spellings that map to it. Matching ignores case, spaces and
 * punctuation, so "Chq./Ref.No.", "chq ref no" and "CHQ/REF NO" are the same header.
 */
public record ColumnSpec(String key, boolean required, List<String> aliases) {

    public ColumnSpec {
        aliases = List.copyOf(aliases);
    }

    public static ColumnSpec required(String key, String... aliases) {
        return new ColumnSpec(key, true, List.of(aliases));
    }

    public static ColumnSpec optional(String key, String... aliases) {
        return new ColumnSpec(key, false, List.of(aliases));
    }

    /** Lowercase, keep only letters and digits (drops spaces, ".", "/", "#", BOM, ...). */
    public static String normalizeHeader(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public boolean matches(String header) {
        return normalizedAliases().contains(normalizeHeader(header));
    }

    /** Index of the first cell matching one of the aliases, or -1. */
    public int indexIn(List<String> headerCells) {
        for (int i = 0; i < headerCells.size(); i++) {
            if (matches(headerCells.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private Set<String> normalizedAliases() {
        return aliases.stream().map(ColumnSpec::normalizeHeader).collect(Collectors.toUnmodifiableSet());
    }
}
