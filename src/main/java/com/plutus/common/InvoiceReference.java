package com.plutus.common;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical form of invoice references, shared by the invoice importer and the narration parser so
 * that "INV1042" on an invoice and "inv 1042" in a bank narration compare equal ("INV-1042").
 * Plain Java, no Spring.
 */
public final class InvoiceReference {

    /** INV, an optional separator (-, space, /, #), then digits (group 1). No anchors or boundaries. */
    public static final String CORE_REGEX = "INV\\s*[-/#]?\\s*(\\d+)";

    private static final Pattern FULL = Pattern.compile("^" + CORE_REGEX + "$", Pattern.CASE_INSENSITIVE);

    private InvoiceReference() {
    }

    /**
     * Normalises an invoice reference to {@code INV-<digits>} when it matches the default pattern;
     * otherwise returns the trimmed, uppercased input unchanged. Null stays null.
     */
    public static String normalize(String reference) {
        if (reference == null) {
            return null;
        }
        String trimmed = reference.strip();
        Matcher m = FULL.matcher(trimmed);
        return m.matches() ? "INV-" + m.group(1) : trimmed.toUpperCase(Locale.ROOT);
    }
}
