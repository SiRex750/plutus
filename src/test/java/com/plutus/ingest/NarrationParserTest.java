package com.plutus.ingest;

import static com.plutus.domain.PaymentSource.BANK_TRANSFER;
import static com.plutus.domain.PaymentSource.UPI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Random;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.plutus.domain.PaymentSource;

class NarrationParserTest {

    private final NarrationParser parser = new NarrationParser();

    static Stream<Arguments> narrations() {
        return Stream.of(
                // --- formats from the spec ---
                row("UPI/CR/412345678901/RAVI KUMAR/SBIN/ravi@okaxis/INV-1042",
                        UPI, "412345678901", "RAVI KUMAR", "ravi@okaxis", "INV-1042"),
                row("UPI-RAVI KUMAR-ravi.k@okhdfcbank-SBIN0001234-412345678901-Payment for INV 1042",
                        UPI, "412345678901", "RAVI KUMAR", "ravi.k@okhdfcbank", "INV-1042"),
                row("UPI/412345678901/Paid via GPay/priya@oksbi/HDFC",
                        UPI, "412345678901", null, "priya@oksbi", null),
                row("NEFT CR-SBIN0001234-ACME TRADERS PVT LTD-INV1042-N123456789012345",
                        BANK_TRANSFER, "N123456789012345", "ACME TRADERS PVT LTD", null, "INV-1042"),
                row("NEFT-HDFCN52026112512345-SHARMA AND SONS-INV/1042",
                        BANK_TRANSFER, "HDFCN52026112512345", "SHARMA AND SONS", null, "INV-1042"),
                row("IMPS/P2A/412345678901/MEENA S/inv1042",
                        BANK_TRANSFER, "412345678901", "MEENA S", null, "INV-1042"),
                row("RTGS CR-UTIB0000123-GLOBAL EXPORTS-UTIBR52026112500012345",
                        BANK_TRANSFER, "UTIBR52026112500012345", "GLOBAL EXPORTS", null, null),
                row("BY TRANSFER-INV-1042-RAJESH",
                        BANK_TRANSFER, null, "RAJESH", null, "INV-1042"),

                // --- variants ---
                row("upi/cr/412345678901/ravi   kumar/sbin/Ravi.K@OkAxis/inv#1042",
                        UPI, "412345678901", "RAVI KUMAR", "ravi.k@okaxis", "INV-1042"),
                row("  UPI/DR/998877665544/  Anita  Desai /anita@ybl  ",
                        UPI, "998877665544", "ANITA DESAI", "anita@ybl", null),
                row("UPI/412345678901/9876543210@paytm/Payment",
                        UPI, "412345678901", null, "9876543210@paytm", null),
                row("Upi-412345678901-KRISHNA & CO.-krishna@okicici-INV 7",
                        UPI, "412345678901", "KRISHNA & CO.", "krishna@okicici", "INV-7"),
                row("neft/n987654321098765/d'souza enterprises/inv-55",
                        BANK_TRANSFER, "N987654321098765", "D'SOUZA ENTERPRISES", null, "INV-55"),
                row("IMPS-412345678901-ICIC0004321-SURESH-INV1042",
                        BANK_TRANSFER, "412345678901", "SURESH", null, "INV-1042"),
                row("RTGS/UTIBR52026112500012345/HDFC0000001/TATA STEEL LTD",
                        BANK_TRANSFER, "UTIBR52026112500012345", "TATA STEEL LTD", null, null),
                // "UPIX" is not the UPI prefix (falls back to BANK_TRANSFER); "123"/"abc1" are not refs or names.
                row("UPIX/123/abc1", BANK_TRANSFER, null, "UPIX", null, null),
                // INV inside a longer word or a VPA is not an invoice reference.
                row("UPI/412345678901/INVESTCO/inv1042@okaxis",
                        UPI, "412345678901", "INVESTCO", "inv1042@okaxis", null),

                // --- garbage ---
                row("????", BANK_TRANSFER, null, null, null, null),
                row("@@@ /// --- ###", BANK_TRANSFER, null, null, null, null),
                row("12/34/5678", BANK_TRANSFER, null, null, null, null));
    }

    private static Arguments row(String narration, PaymentSource source, String ref, String name,
                                 String handle, String hint) {
        return Arguments.of(narration, new ParsedNarration(source, ref, name, handle, hint));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("narrations")
    void parsesAllFields(String narration, ParsedNarration expected) {
        assertThat(parser.parse(narration)).isEqualTo(expected);
    }

    @Test
    void ifscIsNeverMistakenForRefOrName() {
        ParsedNarration neft = parser.parse("NEFT-SBIN0001234-HDFC0000999-ACME TRADERS");
        assertThat(neft.transactionRef()).isNull();
        assertThat(neft.payerName()).isEqualTo("ACME TRADERS");

        ParsedNarration ifscOnly = parser.parse("RTGS/ICIC0000001");
        assertThat(ifscOnly.transactionRef()).isNull();
        assertThat(ifscOnly.payerName()).isNull();

        // Lowercase IFSC is still recognised.
        ParsedNarration lower = parser.parse("NEFT-sbin0001234-N123456789012345");
        assertThat(lower.transactionRef()).isEqualTo("N123456789012345");
        assertThat(lower.payerName()).isNull();
    }

    @Test
    void vpaIsLowercasedAndNeverUsedAsName() {
        ParsedNarration parsed = parser.parse("UPI/412345678901/RAVI.KUMAR@OKAXIS");
        assertThat(parsed.payerHandle()).isEqualTo("ravi.kumar@okaxis");
        assertThat(parsed.payerName()).isNull();
    }

    @Test
    void customInvoicePattern() {
        NarrationParser billParser = new NarrationParser(Pattern.compile("BILL-\\d+", Pattern.CASE_INSENSITIVE));

        ParsedNarration parsed = billParser.parse("UPI/412345678901/RAVI KUMAR/ravi@okaxis/bill-2024");
        assertThat(parsed.referenceHint()).isEqualTo("BILL-2024");
        assertThat(parsed.payerName()).isEqualTo("RAVI KUMAR");
        assertThat(parsed.transactionRef()).isEqualTo("412345678901");

        // The default INV pattern no longer applies.
        assertThat(billParser.parse("NEFT-N123456789012345-ACME-INV-1042").referenceHint()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void nullOrBlankInputGivesEmptyResult(String input) {
        assertThat(parser.parse(input)).isEqualTo(ParsedNarration.empty());
    }

    @Test
    void neverThrowsOnRandomInput() {
        Random random = new Random(42);
        String alphabet = "UPINEFTRGSIMPinv0123456789 /-@#.&'_ABCxyz₹\u0000";
        for (int i = 0; i < 10_000; i++) {
            StringBuilder s = new StringBuilder();
            int length = random.nextInt(80);
            for (int j = 0; j < length; j++) {
                s.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            assertThatCode(() -> parser.parse(s.toString())).as(s.toString()).doesNotThrowAnyException();
        }
    }
}
