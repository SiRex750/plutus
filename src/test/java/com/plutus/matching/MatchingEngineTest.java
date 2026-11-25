package com.plutus.matching;

import static com.plutus.domain.MatchType.EXACT_AMOUNT;
import static com.plutus.domain.MatchType.REFERENCE;
import static com.plutus.domain.PaymentSource.BANK_TRANSFER;
import static com.plutus.domain.PaymentSource.UPI;
import static com.plutus.matching.Decision.AUTO_CONFIRM;
import static com.plutus.matching.Decision.SUGGEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.plutus.domain.PaymentSource;

class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine(new ExactNameMatcher());

    // ---------------------------------------------------------------- fixtures (modelled on samples/)

    private static LocalDate nov(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static long rupees(long r) {
        return r * 100;
    }

    private static InvoiceView invoice(long id, String number, String customer, long outstandingRupees, int issuedNov) {
        return new InvoiceView(id, number, customer, rupees(outstandingRupees), nov(issuedNov), nov(issuedNov).plusDays(14));
    }

    private static PaymentView payment(long id, long rupees, String payer, String ref, int receivedNov) {
        return payment(id, rupees(rupees), payer, ref, receivedNov, UPI);
    }

    private static PaymentView payment(long id, long paise, String payer, String ref, int receivedNov,
                                       PaymentSource source) {
        return new PaymentView(id, paise, payer, null, ref, nov(receivedNov), source);
    }

    private MatchResult run(List<PaymentView> payments, List<InvoiceView> invoices) {
        return engine.match(payments, invoices, MatchingPolicy.DEFAULT);
    }

    private static MatchProposal only(MatchResult result) {
        assertThat(result.proposals()).hasSize(1);
        return result.proposals().get(0);
    }

    private static void assertAllocation(MatchProposal p, long invoiceId, long paise) {
        assertThat(p.allocations()).containsExactly(new AllocationProposal(invoiceId, paise));
    }

    // ---------------------------------------------------------------- pass 1: reference

    @Nested
    class ReferencePass {

        @Test
        void exactReferenceMatchIsAutoConfirmed() {
            MatchProposal p = only(run(
                    List.of(payment(1, 12_500, "RAVI KUMAR", "INV-1001", 3)),
                    List.of(invoice(101, "INV-1001", "Ravi Kumar", 12_500, 1))));

            assertThat(p.type()).isEqualTo(REFERENCE);
            assertThat(p.confidence()).isEqualTo(1.00);
            assertThat(p.decision()).isEqualTo(AUTO_CONFIRM);
            assertAllocation(p, 101, rupees(12_500));
            assertThat(p.reason()).isEqualTo("Invoice reference INV-1001 in narration, amount matches exactly");
        }

        @Test
        void instalmentIsAutoConfirmedAndReducesCapacity() {
            InvoiceView deshpande = invoice(113, "INV-1013", "Deshpande Builders", 60_000, 12);
            MatchResult result = run(List.of(
                            payment(1, 25_000, "DESHPANDE BUILDERS", "INV-1013", 17),
                            // No reference: only matches by amount if capacity dropped to 35,000.
                            payment(2, 35_000, "DESHPANDE BUILDERS", null, 20),
                            // The original full amount must no longer match anything.
                            payment(3, 60_000, "DESHPANDE BUILDERS", null, 21)),
                    List.of(deshpande));

            MatchProposal instalment = result.proposals().get(0);
            assertThat(instalment.type()).isEqualTo(REFERENCE);
            assertThat(instalment.confidence()).isEqualTo(0.95);
            assertThat(instalment.decision()).isEqualTo(AUTO_CONFIRM);
            assertAllocation(instalment, 113, rupees(25_000));
            assertThat(instalment.reason()).contains("partial payment: ₹25,000.00 of ₹60,000.00")
                    .contains("₹35,000.00 still due");

            MatchProposal remainder = result.proposals().get(1);
            assertThat(remainder.paymentId()).isEqualTo(2);
            assertThat(remainder.type()).isEqualTo(EXACT_AMOUNT);
            assertAllocation(remainder, 113, rupees(35_000));

            assertThat(result.unmatchedPaymentIds()).containsExactly(3L);
        }

        @Test
        void overpaymentAllocatesOnlyOutstandingAndIsSuggested() {
            MatchProposal p = only(run(
                    List.of(payment(1, 15_000, "RAVI KUMAR", "INV-1001", 3)),
                    List.of(invoice(101, "INV-1001", "Ravi Kumar", 12_500, 1))));

            assertThat(p.type()).isEqualTo(REFERENCE);
            assertThat(p.confidence()).isEqualTo(0.70);
            assertThat(p.decision()).isEqualTo(SUGGEST);
            assertAllocation(p, 101, rupees(12_500));
            assertThat(p.allocatedPaise()).isLessThan(rupees(15_000));
            assertThat(p.reason()).contains("₹15,000.00").contains("₹12,500.00 allocated")
                    .contains("₹2,500.00 overpaid");
        }

        @Test
        void referenceToUnknownInvoiceFallsThroughToExactAmount() {
            MatchProposal p = only(run(
                    List.of(payment(1, 3_499, null, "INV-9999", 7)),
                    List.of(invoice(105, "INV-1005", "Priya Sharma", 3_499, 5))));

            assertThat(p.type()).isEqualTo(EXACT_AMOUNT);
            assertAllocation(p, 105, rupees(3_499));
            assertThat(p.reason()).startsWith("Invoice reference INV-9999 in narration matches no open invoice.");
        }
    }

    // ---------------------------------------------------------------- pass 2: exact amount

    @Nested
    class ExactAmountPass {

        private final InvoiceView priya = invoice(105, "INV-1005", "Priya Sharma", 3_499, 5);

        @Test
        void matchingNameIsAutoConfirmed() {
            MatchProposal p = only(run(List.of(payment(1, 3_499, "PRIYA SHARMA", null, 7)), List.of(priya)));

            assertThat(p.type()).isEqualTo(EXACT_AMOUNT);
            assertThat(p.confidence()).isEqualTo(0.92);
            assertThat(p.decision()).isEqualTo(AUTO_CONFIRM);
            assertThat(p.reason()).contains("₹3,499.00").contains("INV-1005").contains("matches customer Priya Sharma");
        }

        @Test
        void noPayerNameIsSuggested() {
            MatchProposal p = only(run(List.of(payment(1, 3_499, null, null, 7)), List.of(priya)));

            assertThat(p.confidence()).isEqualTo(0.75);
            assertThat(p.decision()).isEqualTo(SUGGEST);
            assertThat(p.reason()).contains("no payer name");
        }

        @Test
        void differentNameIsSuggestedAtLowConfidence() {
            MatchProposal p = only(run(
                    List.of(payment(1, 7_800, "R. KUMAR", null, 11)),
                    List.of(invoice(110, "INV-1010", "Ravi Kumar", 7_800, 9))));

            assertThat(p.confidence()).isEqualTo(0.55);
            assertThat(p.decision()).isEqualTo(SUGGEST);
            assertThat(p.reason()).contains("\"R. KUMAR\" does not match customer \"Ravi Kumar\"");
        }

        @Test
        void tieWithoutNameEvidenceIsSuggestedAndExplained() {
            MatchProposal p = only(run(
                    List.of(payment(1, 15_000, null, null, 10)),
                    List.of(invoice(201, "INV-2001", "Lakshmi Caterers", 15_000, 1),
                            invoice(202, "INV-2002", "Meena Textiles", 15_000, 5),
                            invoice(203, "INV-2003", "Fatima Boutique", 15_000, 12)))); // issued after payment

            assertThat(p.type()).isEqualTo(EXACT_AMOUNT);
            assertThat(p.confidence()).isEqualTo(0.50);
            assertThat(p.decision()).isEqualTo(SUGGEST);
            assertAllocation(p, 202, rupees(15_000)); // latest issued on or before 10 Nov
            assertThat(p.reason()).contains("3 open invoices are tied").contains("INV-2002");
        }

        @Test
        void tieBrokenByExactlyOneNameMatch() {
            MatchProposal p = only(run(
                    List.of(payment(1, 15_000, "MEENA TEXTILES", null, 10)),
                    List.of(invoice(201, "INV-2001", "Lakshmi Caterers", 15_000, 1),
                            invoice(202, "INV-2002", "Meena Textiles", 15_000, 5))));

            assertThat(p.confidence()).isEqualTo(0.90);
            assertThat(p.decision()).isEqualTo(AUTO_CONFIRM);
            assertAllocation(p, 202, rupees(15_000));
            assertThat(p.reason()).contains("2 open invoices").contains("matches only INV-2002");
        }

        @Test
        void tieWhereAllCandidatesWereIssuedAfterPaymentPicksEarliest() {
            MatchProposal p = only(run(
                    List.of(payment(1, 15_000, null, null, 2)),
                    List.of(invoice(202, "INV-2002", "B", 15_000, 9), invoice(201, "INV-2001", "A", 15_000, 5))));

            assertAllocation(p, 201, rupees(15_000));
            assertThat(p.reason()).contains("earliest issued");
        }
    }

    // ---------------------------------------------------------------- cross-pass guarantees

    @Test
    void weakAmountMatchCannotStealInvoiceNeededByReferenceMatch() {
        InvoiceView krishna = invoice(107, "INV-1007", "Krishna & Co.", 15_750, 6);
        PaymentView amountOnly = payment(1, 15_750, null, null, 1);          // earlier date, processed first
        PaymentView byReference = payment(2, 15_750, "KRISHNA & CO.", "INV-1007", 8);

        MatchResult result = run(List.of(amountOnly, byReference), List.of(krishna));

        MatchProposal p = only(result);
        assertThat(p.paymentId()).isEqualTo(2);
        assertThat(p.type()).isEqualTo(REFERENCE);
        assertThat(result.unmatchedPaymentIds()).containsExactly(1L);
    }

    @Test
    void paymentsReferencingSameInvoiceNeverOverAllocate() {
        InvoiceView inv = invoice(101, "INV-1001", "Ravi Kumar", 10_000, 1);
        MatchResult result = run(List.of(
                        payment(1, 6_000, "RAVI KUMAR", "INV-1001", 3),
                        payment(2, 6_000, "RAVI KUMAR", "INV-1001", 4),
                        payment(3, 1_000, "RAVI KUMAR", "INV-1001", 5)),
                List.of(inv));

        assertThat(result.proposals()).hasSize(2);
        MatchProposal first = result.proposals().get(0);
        MatchProposal second = result.proposals().get(1);
        assertThat(first.confidence()).isEqualTo(0.95);
        assertAllocation(first, 101, rupees(6_000));
        assertThat(second.confidence()).isEqualTo(0.70);           // capped: treated as overpayment
        assertAllocation(second, 101, rupees(4_000));
        assertThat(second.reason()).contains("₹2,000.00 overpaid");

        long total = result.proposals().stream().mapToLong(MatchProposal::allocatedPaise).sum();
        assertThat(total).isEqualTo(inv.outstandingPaise());

        // Third payment: invoice already fully allocated; no other candidate, so unmatched.
        assertThat(result.unmatchedPaymentIds()).containsExactly(3L);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 7, 42, 1234, 99_999})
    void shuffledInputGivesIdenticalOutput(long seed) {
        MatchResult expected = run(samplePayments(), sampleInvoices());

        List<PaymentView> payments = new ArrayList<>(samplePayments());
        List<InvoiceView> invoices = new ArrayList<>(sampleInvoices());
        Random random = new Random(seed);
        Collections.shuffle(payments, random);
        Collections.shuffle(invoices, random);

        assertThat(run(payments, invoices)).isEqualTo(expected);
    }

    @Test
    void confidenceBelowSuggestThresholdGivesNoProposal() {
        MatchingPolicy strict = new MatchingPolicy(0.95, 0.80);
        InvoiceView priya = invoice(105, "INV-1005", "Priya Sharma", 3_499, 5);

        MatchResult result = engine.match(List.of(payment(1, 3_499, null, null, 7)), List.of(priya), strict);

        assertThat(result.proposals()).isEmpty();
        assertThat(result.unmatchedPaymentIds()).containsExactly(1L);
    }

    @Test
    void rejectedLowConfidenceMatchDoesNotConsumeCapacity() {
        MatchingPolicy strict = new MatchingPolicy(0.95, 0.80);
        InvoiceView priya = invoice(105, "INV-1005", "Priya Sharma", 3_499, 5);

        // Payment 1 (no name, 0.75) is below threshold; payment 2 by reference still gets the full invoice.
        MatchResult result = engine.match(List.of(
                payment(1, 3_499, null, null, 6),
                payment(2, 3_499, "PRIYA SHARMA", "INV-1005", 7)), List.of(priya), strict);

        MatchProposal p = only(result);
        assertThat(p.paymentId()).isEqualTo(2);
        assertThat(result.unmatchedPaymentIds()).containsExactly(1L);
    }

    @Test
    void sampleStatementScenario() {
        MatchResult result = run(samplePayments(), sampleInvoices());

        assertThat(result.proposals())
                .extracting(MatchProposal::paymentId, p -> p.allocations().get(0).invoiceId(),
                        MatchProposal::type, MatchProposal::confidence, MatchProposal::decision)
                .containsExactly(
                        tuple(1, 1001, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(2, 1002, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(3, 1003, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(4, 1004, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(5, 1005, EXACT_AMOUNT, 0.75, SUGGEST),       // GPay, no name
                        tuple(6, 1007, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(7, 1010, EXACT_AMOUNT, 0.55, SUGGEST),       // "R. KUMAR" vs "Ravi Kumar"
                        tuple(8, 1009, REFERENCE, 1.00, AUTO_CONFIRM),
                        tuple(10, 1006, EXACT_AMOUNT, 0.92, AUTO_CONFIRM), // Global Exports, no reference
                        tuple(11, 1013, REFERENCE, 0.95, AUTO_CONFIRM),    // instalment
                        tuple(12, 1014, EXACT_AMOUNT, 0.92, AUTO_CONFIRM), // Fatima Boutique
                        tuple(13, 1015, REFERENCE, 1.00, AUTO_CONFIRM));
        // Lakshmi's 15,000 covers two invoices (split: later prompt); refund, cash and interest match nothing.
        assertThat(result.unmatchedPaymentIds()).containsExactly(9L, 14L, 15L, 16L);
    }

    private static org.assertj.core.groups.Tuple tuple(long paymentId, long invoiceId, Object type, double confidence,
                                                       Decision decision) {
        return org.assertj.core.groups.Tuple.tuple(paymentId, invoiceId, type, confidence, decision);
    }

    private static List<InvoiceView> sampleInvoices() {
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

    private static List<PaymentView> samplePayments() {
        return List.of(
                payment(1, rupees(12_500), "RAVI KUMAR", "INV-1001", 3, UPI),
                payment(2, rupees(45_000), "SHARMA AND SONS", "INV-1002", 4, BANK_TRANSFER),
                payment(3, rupees(118_000), "ACME TRADERS PVT LTD", "INV-1003", 5, BANK_TRANSFER),
                payment(4, rupees(8_250), "MEENA S", "INV-1004", 6, BANK_TRANSFER),
                payment(5, rupees(3_499), null, null, 7, UPI),
                payment(6, rupees(15_750), "KRISHNA & CO.", "INV-1007", 8, UPI),
                payment(7, rupees(7_800), "R. KUMAR", null, 11, UPI),
                payment(8, rupees(22_000), "SURESH ELECTRICALS", "INV-1009", 12, BANK_TRANSFER),
                payment(9, rupees(15_000), "LAKSHMI CATERERS", null, 13, UPI),
                payment(10, rupees(250_000), "GLOBAL EXPORTS", null, 15, BANK_TRANSFER),
                payment(11, rupees(25_000), "DESHPANDE BUILDERS", "INV-1013", 17, BANK_TRANSFER),
                payment(12, rupees(4_200), "FATIMA BOUTIQUE", null, 20, UPI),
                payment(13, rupees(11_111), "D'SOUZA JOSEPH", "INV-1015", 20, BANK_TRANSFER),
                payment(14, rupees(349), "AMAZON SELLER SERVICES", null, 21, UPI),
                payment(15, rupees(5_000), null, null, 23, BANK_TRANSFER),
                payment(16, 12_550, null, null, 24, BANK_TRANSFER));
    }

    @Test
    void confidencesAreWithinRangeAndAllocationsPositive() {
        MatchResult result = run(samplePayments(), sampleInvoices());
        for (MatchProposal p : result.proposals()) {
            assertThat(p.confidence()).isBetween(0.0, 1.0);
            assertThat(p.reason()).isNotBlank();
            assertThat(p.allocatedPaise()).isPositive();
        }
        assertThat(result.proposals().get(0).confidence()).isCloseTo(1.0, within(1e-9));
    }
}
