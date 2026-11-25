package com.plutus.matching;

import static com.plutus.domain.MatchType.EXACT_AMOUNT;
import static com.plutus.domain.MatchType.FUZZY;
import static com.plutus.domain.MatchType.REFERENCE;
import static com.plutus.domain.PaymentSource.BANK_TRANSFER;
import static com.plutus.domain.PaymentSource.UPI;
import static com.plutus.matching.Decision.AUTO_CONFIRM;
import static com.plutus.matching.Decision.SUGGEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.plutus.common.Money;
import com.plutus.domain.PaymentSource;

/** The engine with its default {@link FuzzyNameMatcher}. */
class FuzzyMatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine();

    private static LocalDate nov(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static long rupees(long r) {
        return r * 100;
    }

    private static InvoiceView invoice(long id, String number, String customer, long rupees, int issuedNov) {
        return new InvoiceView(id, number, customer, rupees(rupees), nov(issuedNov), nov(issuedNov).plusDays(14));
    }

    private static PaymentView payment(long id, long paise, String payer, String handle, String ref, LocalDate date,
                                       PaymentSource source) {
        return new PaymentView(id, paise, payer, handle, ref, date, source);
    }

    private static PaymentView payment(long id, long rupees, String payer, String handle, String ref, int nov) {
        return payment(id, rupees(rupees), payer, handle, ref, nov(nov), UPI);
    }

    private MatchProposal only(MatchResult result) {
        assertThat(result.proposals()).hasSize(1);
        return result.proposals().get(0);
    }

    // ---------------------------------------------------------------- pass 2 with graded names

    @Test
    void initialAndSurnameIsSuggestedViaExactAmountWithSimilarityShown() {
        MatchProposal p = only(engine.match(
                List.of(payment(7, 7_800, "R. KUMAR", "rkumar@okicici", null, 11)),
                List.of(invoice(1010, "INV-1010", "Ravi Kumar", 7_800, 9)),
                MatchingPolicy.DEFAULT));

        assertThat(p.type()).isEqualTo(EXACT_AMOUNT);
        assertThat(p.confidence()).isEqualTo(0.80);
        assertThat(p.decision()).isEqualTo(SUGGEST);
        assertThat(p.reason())
                .contains("\"R. KUMAR\" looks similar to customer \"Ravi Kumar\"")
                .contains("name similarity 0.88")
                .contains("initial R matches RAVI");
    }

    @Test
    void fullNameUpiHandleConfirmsExactAmount() {
        MatchProposal p = only(engine.match(
                List.of(payment(5, 3_499, null, "priya.sharma@oksbi", null, 7)),
                List.of(invoice(1005, "INV-1005", "Priya Sharma", 3_499, 5)),
                MatchingPolicy.DEFAULT));

        assertThat(p.confidence()).isEqualTo(0.92);
        assertThat(p.decision()).isEqualTo(AUTO_CONFIRM);
        assertThat(p.reason()).contains("UPI handle priya.sharma resembles Priya Sharma");
    }

    @Test
    void tieIsBrokenBySimilarName() {
        MatchProposal p = only(engine.match(
                List.of(payment(1, 15_000, "R KUMAR", null, null, 10)),
                List.of(invoice(201, "INV-2001", "Lakshmi Caterers", 15_000, 1),
                        invoice(202, "INV-2002", "Ravi Kumar", 15_000, 5)),
                MatchingPolicy.DEFAULT));

        assertThat(p.allocations()).containsExactly(new AllocationProposal(202, rupees(15_000)));
        assertThat(p.confidence()).isEqualTo(0.80);
        assertThat(p.reason()).contains("looks similar only to customer \"Ravi Kumar\"");
    }

    // ---------------------------------------------------------------- pass 3: fuzzy

    @Test
    void fuzzyPassPicksInvoiceByNameForInstalment() {
        MatchProposal p = only(engine.match(
                List.of(payment(1, 20_000, "DESHPANDE BUILDRS", null, null, 20)),
                List.of(invoice(1013, "INV-1013", "Deshpande Builders", 60_000, 12),
                        invoice(1020, "INV-1020", "Desai Constructions", 40_000, 12),
                        invoice(1021, "INV-1021", "Sharma Traders", 30_000, 12)),
                MatchingPolicy.DEFAULT));

        assertThat(p.type()).isEqualTo(FUZZY);
        assertThat(p.decision()).isEqualTo(SUGGEST);
        assertThat(p.allocations()).containsExactly(new AllocationProposal(1013, rupees(20_000)));
        assertThat(p.confidence()).isLessThanOrEqualTo(0.89);
        assertThat(p.reason())
                .contains("Payer \"DESHPANDE BUILDRS\" looks like customer \"Deshpande Builders\"")
                .contains("BUILDRS is close to BUILDERS")
                .contains("less than the ₹60,000.00 outstanding (possible instalment)")
                .contains("within the invoice's payment window");
    }

    @Test
    void fuzzyPrefersTheBetterNameAmongSameCustomerLookalikes() {
        // Both invoices could take an instalment; only one customer name is close.
        MatchProposal p = only(engine.match(
                List.of(payment(1, 5_000, "SURESH ELECTRICALS", null, null, 20)),
                List.of(invoice(1, "INV-1", "Suresh Electrical Works", 30_000, 12),
                        invoice(2, "INV-2", "Suresh Electricals", 30_000, 12)),
                MatchingPolicy.DEFAULT));

        assertThat(p.allocations().get(0).invoiceId()).isEqualTo(2);
    }

    @Test
    void fuzzyIsNeverAutoConfirmed() {
        // A lenient policy would auto-confirm anything >= 0.60; the fuzzy pass still only suggests.
        MatchingPolicy lenient = new MatchingPolicy(0.60, 0.30);
        MatchProposal p = only(engine.match(
                List.of(payment(1, 25_000, "DESHPANDE BUILDERS", null, null, 17)),
                List.of(invoice(1013, "INV-1013", "Deshpande Builders", 60_000, 12)),
                lenient));

        assertThat(p.type()).isEqualTo(FUZZY);
        assertThat(p.decision()).isEqualTo(SUGGEST);
        assertThat(p.confidence()).isLessThanOrEqualTo(0.89);
    }

    @Test
    void perfectFuzzyScoreIsCappedBelowAutoConfirm() {
        assertThat(MatchingEngine.fuzzyScore(1.0, 1.0, 1.0)).isCloseTo(1.0, within(1e-9));
        assertThat(Math.min(MatchingEngine.fuzzyScore(1.0, 1.0, 1.0), MatchingEngine.FUZZY_MAX_CONFIDENCE))
                .isLessThan(MatchingPolicy.DEFAULT.autoConfirmThreshold());
    }

    @Test
    void noFuzzyMatchBelowNameThresholdEvenWithExactAmountAndPerfectDate() {
        // suggest threshold 0.60 rejects pass 2's 0.55 "names differ" proposal, so the payment reaches pass 3.
        MatchingPolicy policy = new MatchingPolicy(0.90, 0.60);
        MatchResult result = engine.match(
                List.of(payment(1, 7_800, "GLOBAL EXPORTS", null, null, 11)),
                List.of(invoice(1010, "INV-1010", "Ravi Kumar", 7_800, 9)),
                policy);

        assertThat(result.proposals()).isEmpty();
        assertThat(result.unmatchedPaymentIds()).containsExactly(1L);
    }

    @Test
    void fuzzyNeverMatchesOnAmountAndDateAlone() {
        MatchResult result = engine.match(
                List.of(payment(1, 5_000, null, null, null, 20)),
                List.of(invoice(1013, "INV-1013", "Deshpande Builders", 60_000, 12)),
                MatchingPolicy.DEFAULT);

        assertThat(result.proposals()).isEmpty();
    }

    @Test
    void fuzzyDoesNotTakeOverpayments() {
        MatchResult result = engine.match(
                List.of(payment(1, 15_000, "LAKSHMI CATERERS", "lakshmicaterers@ybl", null, 13)),
                List.of(invoice(1011, "INV-1011", "Lakshmi Caterers", 9_450, 10),
                        invoice(1012, "INV-1012", "Lakshmi Caterers", 5_550, 12)),
                MatchingPolicy.DEFAULT);

        assertThat(result.proposals()).isEmpty(); // a split across both: later prompt
    }

    @Test
    void dateScoreWindowAndDecay() {
        InvoiceView inv = new InvoiceView(1, "INV-1", "A", 100, nov(1), nov(15)); // window ends 15 Dec
        assertThat(MatchingEngine.dateScore(nov(1), inv)).isEqualTo(1.0);
        assertThat(MatchingEngine.dateScore(LocalDate.of(2026, 12, 15), inv)).isEqualTo(1.0);
        assertThat(MatchingEngine.dateScore(LocalDate.of(2027, 1, 14), inv)).isCloseTo(0.5, within(1e-9));
        assertThat(MatchingEngine.dateScore(LocalDate.of(2027, 2, 13), inv)).isZero();
        assertThat(MatchingEngine.dateScore(LocalDate.of(2027, 6, 1), inv)).isZero();
        assertThat(MatchingEngine.dateScore(LocalDate.of(2026, 10, 30), inv)).isEqualTo(0.3);

        InvoiceView noDue = new InvoiceView(2, "INV-2", "A", 100, nov(1), null); // issue + 30 + 30
        assertThat(MatchingEngine.dateScore(LocalDate.of(2026, 12, 31), noDue)).isEqualTo(1.0);
        assertThat(MatchingEngine.dateScore(LocalDate.of(2027, 1, 30), noDue)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void amountScores() {
        assertThat(MatchingEngine.amountScore(100, 100)).isEqualTo(1.0);
        assertThat(MatchingEngine.amountScore(40, 100)).isEqualTo(0.6);
        assertThat(MatchingEngine.amountScore(140, 100)).isZero();
    }

    // ---------------------------------------------------------------- full sample statement

    @Test
    void sampleStatementWithFuzzyMatching() {
        List<InvoiceView> invoices = sampleInvoices();
        MatchResult result = engine.match(samplePayments(), invoices, MatchingPolicy.DEFAULT);

        printTable(result, samplePayments(), invoices);

        assertThat(result.proposals())
                .extracting(MatchProposal::paymentId, p -> p.allocations().get(0).invoiceId(),
                        MatchProposal::type, MatchProposal::confidence, MatchProposal::decision)
                .containsExactly(
                        tuple(1L, 1001L, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(2L, 1002L, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(3L, 1003L, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(4L, 1004L, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(5L, 1005L, EXACT_AMOUNT, 0.92, AUTO_CONFIRM), // handle priya.sharma now confirms
                        tuple(6L, 1007L, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(7L, 1010L, EXACT_AMOUNT, 0.80, SUGGEST),      // R. KUMAR ~ Ravi Kumar (was 0.55)
                        tuple(8L, 1009L, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(10L, 1006L, EXACT_AMOUNT, 0.92, AUTO_CONFIRM),
                        tuple(11L, 1013L, REFERENCE, 0.95, AUTO_CONFIRM),
                        tuple(12L, 1014L, EXACT_AMOUNT, 0.92, AUTO_CONFIRM),
                        tuple(13L, 1015L, REFERENCE, 1.00, AUTO_CONFIRM));
        assertThat(result.unmatchedPaymentIds()).containsExactly(9L, 14L, 15L, 16L);
    }

    @Test
    void shuffledSampleGivesIdenticalOutput() {
        MatchResult expected = engine.match(samplePayments(), sampleInvoices(), MatchingPolicy.DEFAULT);
        for (long seed : new long[] {3, 17, 2026}) {
            List<PaymentView> payments = new ArrayList<>(samplePayments());
            List<InvoiceView> invoices = new ArrayList<>(sampleInvoices());
            Random random = new Random(seed);
            Collections.shuffle(payments, random);
            Collections.shuffle(invoices, random);
            assertThat(engine.match(payments, invoices, MatchingPolicy.DEFAULT)).isEqualTo(expected);
        }
    }

    private static void printTable(MatchResult result, List<PaymentView> payments, List<InvoiceView> invoices) {
        Map<Long, PaymentView> pay = payments.stream().collect(Collectors.toMap(PaymentView::id, Function.identity()));
        Map<Long, InvoiceView> inv = invoices.stream().collect(Collectors.toMap(InvoiceView::id, Function.identity()));
        StringBuilder out = new StringBuilder("\nSample statement through the fuzzy engine\n");
        String row = "%-3s %-26s %14s  %-9s %-13s %5s  %s%n";
        out.append(String.format(Locale.ROOT, row, "#", "payer", "amount", "invoice", "type", "conf", "decision"));
        for (MatchProposal p : result.proposals()) {
            PaymentView payment = pay.get(p.paymentId());
            out.append(String.format(Locale.ROOT, row, p.paymentId(), payerOf(payment),
                    rs(payment.unallocatedPaise()),
                    inv.get(p.allocations().get(0).invoiceId()).invoiceNumber(), p.type(),
                    String.format(Locale.ROOT, "%.2f", p.confidence()), p.decision()));
        }
        for (Long id : result.unmatchedPaymentIds()) {
            PaymentView payment = pay.get(id);
            out.append(String.format(Locale.ROOT, row, id, payerOf(payment),
                    rs(payment.unallocatedPaise()), "-", "UNMATCHED", "", ""));
        }
        System.out.print(out);
    }

    /** ASCII amount for console output (Windows consoles often can't print ₹). */
    private static String rs(long paise) {
        return Money.formatPaise(paise).replace("₹", "Rs ");
    }

    private static String payerOf(PaymentView p) {
        String s = p.payerName() != null ? p.payerName() : p.payerHandle() != null ? p.payerHandle() : "(none)";
        return s.length() > 26 ? s.substring(0, 25) + "…" : s;
    }

    /** Same invoices as samples/invoices.csv. */
    static List<InvoiceView> sampleInvoices() {
        return List.of(
                invoice(1001, "INV-1001", "Ravi Kumar", 12_500, 1),
                invoice(1002, "INV-1002", "Sharma and Sons", 45_000, 2),
                invoice(1003, "INV-1003", "Acme Traders Pvt Ltd", 118_000, 3),
                invoice(1004, "INV-1004", "Meena Textiles", 8_250, 4),
                invoice(1005, "INV-1005", "Priya Sharma", 3_499, 5),
                invoice(1006, "INV-1006", "Global Exports", 250_000, 5),
                invoice(1007, "INV-1007", "Krishna & Co.", 15_750, 6),
                invoice(1008, "INV-1008", "Anita Desai", 6_000, 7),
                invoice(1009, "INV-1009", "Suresh Electricals", 22_000, 8),
                invoice(1010, "INV-1010", "Ravi Kumar", 7_800, 9),
                invoice(1011, "INV-1011", "Lakshmi Caterers", 9_450, 10),
                invoice(1012, "INV-1012", "Lakshmi Caterers", 5_550, 12),
                invoice(1013, "INV-1013", "Deshpande Builders", 60_000, 12),
                invoice(1014, "INV-1014", "Fatima Boutique", 4_200, 14),
                invoice(1015, "INV-1015", "Joseph D'Souza", 11_111, 15));
    }

    /** The 16 credits the importer stores from samples/bank-statement-hdfc-style.csv, with parsed handles. */
    static List<PaymentView> samplePayments() {
        return List.of(
                payment(1, rupees(12_500), "RAVI KUMAR", "ravi@okaxis", "INV-1001", nov(3), UPI),
                payment(2, rupees(45_000), "SHARMA AND SONS", null, "INV-1002", nov(4), BANK_TRANSFER),
                payment(3, rupees(118_000), "ACME TRADERS PVT LTD", null, "INV-1003", nov(5), BANK_TRANSFER),
                payment(4, rupees(8_250), "MEENA S", null, "INV-1004", nov(6), BANK_TRANSFER),
                payment(5, rupees(3_499), null, "priya.sharma@oksbi", null, nov(7), UPI),
                payment(6, rupees(15_750), "KRISHNA & CO.", "krishna@okicici", "INV-1007", nov(8), UPI),
                payment(7, rupees(7_800), "R. KUMAR", "rkumar@okicici", null, nov(11), UPI),
                payment(8, rupees(22_000), "SURESH ELECTRICALS", null, "INV-1009", nov(12), BANK_TRANSFER),
                payment(9, rupees(15_000), "LAKSHMI CATERERS", "lakshmicaterers@ybl", null, nov(13), UPI),
                payment(10, rupees(250_000), "GLOBAL EXPORTS", null, null, nov(15), BANK_TRANSFER),
                payment(11, rupees(25_000), "DESHPANDE BUILDERS", null, "INV-1013", nov(17), BANK_TRANSFER),
                payment(12, rupees(4_200), "FATIMA BOUTIQUE", "fatima@okaxis", null, nov(20), UPI),
                payment(13, rupees(11_111), "D'SOUZA JOSEPH", null, "INV-1015", nov(20), BANK_TRANSFER),
                payment(14, rupees(349), "AMAZON SELLER SERVICES", "amazonseller@apl", null, nov(21), UPI),
                payment(15, rupees(5_000), null, null, null, nov(23), BANK_TRANSFER),
                payment(16, 12_550, null, null, null, nov(24), BANK_TRANSFER));
    }
}
