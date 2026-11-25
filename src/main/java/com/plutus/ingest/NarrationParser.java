package com.plutus.ingest;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import com.plutus.common.InvoiceReference;
import com.plutus.domain.PaymentSource;

/**
 * Extracts structured fields from the free-text narration of an Indian bank / UPI statement line,
 * e.g. {@code UPI/CR/412345678901/RAVI KUMAR/SBIN/ravi@okaxis/INV-1042}.
 *
 * <p>Plain Java, stateless and thread-safe. Never throws on bad input: anything it cannot identify
 * with confidence is returned as null rather than guessed.
 *
 * <p>Approach: detect the rail from the prefix, pull the invoice reference out of the whole text
 * first (its own separators would otherwise break tokenisation), then split on {@code /} and
 * {@code -} and classify each token, in this order: VPA, IFSC (skipped), transaction ref, payer name.
 */
public final class NarrationParser {

    /**
     * {@link InvoiceReference#CORE_REGEX} (INV, optional -, space, / or #, digits), not embedded in a
     * longer word or a VPA.
     */
    public static final Pattern DEFAULT_INVOICE_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])" + InvoiceReference.CORE_REGEX + "(?![A-Za-z0-9@])", Pattern.CASE_INSENSITIVE);

    private static final Function<MatchResult, String> DEFAULT_INVOICE_NORMALISER =
            m -> InvoiceReference.normalize(m.group());

    private static final Pattern SOURCE_PREFIX =
            Pattern.compile("^\\s*(UPI|NEFT|RTGS|IMPS)(?=$|[\\s/-])", Pattern.CASE_INSENSITIVE);

    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[/-]");

    /** IFSC: 4 letters, a literal 0, then 6 alphanumerics (e.g. SBIN0001234). */
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    /** UPI VPA: handle@psp, no spaces. */
    private static final Pattern VPA = Pattern.compile("^[a-z0-9._]+@[a-z][a-z0-9.]*$", Pattern.CASE_INSENSITIVE);

    /** UPI / IMPS RRN: exactly 12 digits. */
    private static final Pattern UPI_REF = Pattern.compile("^\\d{12}$");

    /**
     * Bank refs: a 12-digit RRN, or 1-5 letters then 10-22 digits
     * (N123456789012345, HDFCN52026112512345, UTIBR52026112500012345).
     */
    private static final Pattern BANK_REF = Pattern.compile("^(?:\\d{12}|[A-Z]{1,5}\\d{10,22})$");

    /** Alphabetic words with spaces, &, . and apostrophes. */
    private static final Pattern NAME_SHAPE = Pattern.compile("^[A-Z][A-Z.&' ]*$");

    /** Words that mark a token as structural/noise rather than a payer name. */
    private static final Set<String> KEYWORDS = Set.of(
            "UPI", "CR", "DR", "P2A", "P2M", "P2P", "NEFT", "RTGS", "IMPS", "MMT",
            "BY", "TRANSFER", "TRF", "TO", "FROM", "PAID", "PAY", "VIA", "PAYMENT", "FOR", "REF",
            "CREDIT", "DEBIT", "INB", "MOB", "NA", "GPAY", "PHONEPE", "PAYTM", "BHIM", "AMAZONPAY");

    /** Bank short codes that appear as standalone tokens. */
    private static final Set<String> BANK_CODES = Set.of(
            "SBIN", "SBI", "HDFC", "ICIC", "ICICI", "AXIS", "UTIB", "KKBK", "KOTAK", "PUNB", "PNB",
            "BARB", "BOB", "YESB", "YES", "IDIB", "CNRB", "UBIN", "IDFB", "IDFC", "INDB", "FDRL", "CITI");

    private final Pattern invoicePattern;
    private final Function<MatchResult, String> invoiceNormaliser;

    /** Uses {@link #DEFAULT_INVOICE_PATTERN}, normalising matches via {@link InvoiceReference#normalize}. */
    public NarrationParser() {
        this(DEFAULT_INVOICE_PATTERN, DEFAULT_INVOICE_NORMALISER);
    }

    /** Custom invoice pattern; the hint is the whole match, uppercased with whitespace removed. */
    public NarrationParser(Pattern invoicePattern) {
        this(invoicePattern, m -> m.group().replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
    }

    public NarrationParser(Pattern invoicePattern, Function<MatchResult, String> invoiceNormaliser) {
        this.invoicePattern = Objects.requireNonNull(invoicePattern, "invoicePattern");
        this.invoiceNormaliser = Objects.requireNonNull(invoiceNormaliser, "invoiceNormaliser");
    }

    public ParsedNarration parse(String narration) {
        if (narration == null || narration.isBlank()) {
            return ParsedNarration.empty();
        }

        PaymentSource source = detectSource(narration);

        // Pull the invoice reference out first: "INV-1042" / "INV/1042" contain our token separators.
        String referenceHint = null;
        Matcher invoice = invoicePattern.matcher(narration);
        String remainder = narration;
        if (invoice.find()) {
            referenceHint = invoiceNormaliser.apply(invoice.toMatchResult());
            remainder = narration.substring(0, invoice.start()) + "/" + narration.substring(invoice.end());
        }

        String transactionRef = null;
        String payerName = null;
        String payerHandle = null;
        Pattern refPattern = source == PaymentSource.UPI ? UPI_REF : BANK_REF;

        for (String raw : TOKEN_SEPARATOR.split(remainder)) {
            String token = raw.strip();
            if (token.isEmpty()) {
                continue;
            }
            String upper = token.toUpperCase(Locale.ROOT);

            if (VPA.matcher(token).matches()) {
                if (payerHandle == null) {
                    payerHandle = token.toLowerCase(Locale.ROOT);
                }
            } else if (IFSC.matcher(upper).matches()) {
                continue; // branch code of the remitter's bank: never a ref or a name
            } else if (refPattern.matcher(upper).matches()) {
                if (transactionRef == null) {
                    transactionRef = upper;
                }
            } else if (payerName == null) {
                payerName = asPayerName(upper);
            }
        }

        return new ParsedNarration(source, transactionRef, payerName, payerHandle, referenceHint);
    }

    private static PaymentSource detectSource(String narration) {
        Matcher m = SOURCE_PREFIX.matcher(narration);
        if (m.find() && m.group(1).equalsIgnoreCase("UPI")) {
            return PaymentSource.UPI;
        }
        return PaymentSource.BANK_TRANSFER; // NEFT/RTGS/IMPS, and the fallback for anything else
    }

    /** Returns the normalised name if the token looks like one, else null. */
    private static String asPayerName(String upperToken) {
        String name = upperToken.replaceAll("\\s+", " ").strip();
        if (!NAME_SHAPE.matcher(name).matches() || BANK_CODES.contains(name)) {
            return null;
        }
        boolean hasRealWord = false;
        for (String word : name.split(" ")) {
            String bare = word.replaceAll("[.&']", "");
            if (KEYWORDS.contains(bare)) {
                return null;
            }
            if (bare.length() >= 2) {
                hasRealWord = true;
            }
        }
        return hasRealWord ? name : null;
    }
}
