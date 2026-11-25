package com.plutus.common;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money utilities. All amounts in PLUTUS are {@code long} minor units (paise).
 * Plain Java, no Spring, no floating point.
 */
public final class Money {

    private static final String RUPEE = "₹";

    /** Optional currency prefix (₹, Rs, Rs., INR), then the number. */
    private static final Pattern INPUT = Pattern.compile(
            "^(?:" + RUPEE + "|rs\\.?|inr)?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Plain digits, or correctly grouped digits (western 1,234,567 or Indian 12,34,567),
     * with at most two decimal places.
     */
    private static final Pattern NUMBER = Pattern.compile(
            "^(\\d+|\\d{1,3}(?:,\\d{3})+|\\d{1,2}(?:,\\d{2})+,\\d{3})(?:\\.(\\d{1,2}))?$");

    private Money() {
    }

    /**
     * Parses a rupee amount such as {@code "1234"}, {@code "1,234.50"}, {@code "₹1,234.50"},
     * {@code "Rs. 1,234.50"} or {@code "INR 1234.50"} into paise.
     *
     * @throws IllegalArgumentException for null/blank/garbage input, negative amounts,
     *                                  more than two decimal places, or values that overflow a long
     */
    public static long parseRupeesToPaise(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("Amount is empty");
        }
        Matcher prefixed = INPUT.matcher(s.strip());
        if (!prefixed.matches()) {
            throw new IllegalArgumentException("Not a valid amount: '" + s + "'");
        }
        String number = prefixed.group(1).strip();
        if (number.startsWith("-")) {
            throw new IllegalArgumentException("Negative amounts are not allowed: '" + s + "'");
        }
        if (!NUMBER.matcher(number).matches()) {
            throw new IllegalArgumentException("Not a valid amount: '" + s + "'");
        }
        try {
            return new BigDecimal(number.replace(",", "")).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount out of range: '" + s + "'", e);
        }
    }

    /** Formats paise as rupees with Indian digit grouping, e.g. 12345678900 → "₹12,34,56,789.00". */
    public static String formatPaise(long paise) {
        long rupees = Math.abs(paise / 100);
        long fraction = Math.abs(paise % 100);
        String sign = paise < 0 ? "-" : "";
        return sign + RUPEE + groupIndian(Long.toString(rupees)) + "." + (fraction < 10 ? "0" : "") + fraction;
    }

    /** Last three digits form one group; everything before is grouped in twos. */
    private static String groupIndian(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String head = digits.substring(0, digits.length() - 3);
        String tail = digits.substring(digits.length() - 3);
        StringBuilder out = new StringBuilder();
        int firstGroup = head.length() % 2 == 0 ? 2 : 1;
        out.append(head, 0, firstGroup);
        for (int i = firstGroup; i < head.length(); i += 2) {
            out.append(',').append(head, i, i + 2);
        }
        return out.append(',').append(tail).toString();
    }
}
