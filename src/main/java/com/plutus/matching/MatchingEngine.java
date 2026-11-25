package com.plutus.matching;

import static com.plutus.common.Money.formatPaise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.plutus.domain.MatchType;

/**
 * Proposes payment → invoice matches. Pure Java: works on immutable views, never touches the database.
 *
 * <p>Runs two passes over all payments so a weak match can never take an invoice a strong match needs:
 * <ol>
 *   <li><b>Reference</b>: the payment's invoice reference names an open invoice.</li>
 *   <li><b>Exact amount</b>: for payments still unmatched, an open invoice whose remaining outstanding
 *       equals the payment amount, using the payer name to confirm or disambiguate.</li>
 * </ol>
 * Each invoice's remaining outstanding is tracked across the whole run, so proposals never over-allocate.
 * Payments are processed by (receivedDate, id) and invoices by id, so output is deterministic.
 */
public final class MatchingEngine {

    static final double REFERENCE_EXACT = 1.00;
    static final double REFERENCE_PARTIAL = 0.95;
    static final double REFERENCE_OVERPAID = 0.70;
    static final double AMOUNT_NAME_MATCH = 0.92;
    static final double AMOUNT_NO_NAME = 0.75;
    static final double AMOUNT_NAME_MISMATCH = 0.55;
    static final double AMOUNT_TIE_ONE_NAME_MATCH = 0.90;
    static final double AMOUNT_TIE = 0.50;

    private static final Comparator<PaymentView> PAYMENT_ORDER =
            Comparator.comparing(PaymentView::receivedDate).thenComparingLong(PaymentView::id);

    private final NameMatcher nameMatcher;

    public MatchingEngine(NameMatcher nameMatcher) {
        this.nameMatcher = Objects.requireNonNull(nameMatcher, "nameMatcher");
    }

    public MatchResult match(List<PaymentView> payments, List<InvoiceView> invoices, MatchingPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        List<PaymentView> ordered = payments.stream()
                .filter(p -> p.unallocatedPaise() > 0)
                .sorted(PAYMENT_ORDER)
                .toList();
        Capacity capacity = new Capacity(invoices);

        Map<Long, MatchProposal> proposals = new HashMap<>();
        Map<Long, String> fallThroughNotes = new HashMap<>();

        // Pass 1: reference matches claim their invoices first.
        for (PaymentView payment : ordered) {
            referenceMatch(payment, capacity, policy, fallThroughNotes)
                    .ifPresent(p -> proposals.put(payment.id(), p));
        }

        // Pass 2: exact amount, only for payments pass 1 left unmatched, only against remaining capacity.
        for (PaymentView payment : ordered) {
            if (!proposals.containsKey(payment.id())) {
                exactAmountMatch(payment, capacity, policy, fallThroughNotes.getOrDefault(payment.id(), ""))
                        .ifPresent(p -> proposals.put(payment.id(), p));
            }
        }

        List<MatchProposal> inOrder = new ArrayList<>();
        List<Long> unmatched = new ArrayList<>();
        for (PaymentView payment : ordered) {
            MatchProposal proposal = proposals.get(payment.id());
            if (proposal != null) {
                inOrder.add(proposal);
            } else {
                unmatched.add(payment.id());
            }
        }
        return new MatchResult(inOrder, unmatched);
    }

    // ---------------------------------------------------------------- pass 1

    private Optional<MatchProposal> referenceMatch(PaymentView payment, Capacity capacity, MatchingPolicy policy,
                                                   Map<Long, String> fallThroughNotes) {
        String ref = payment.referenceHint();
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        Optional<InvoiceView> found = capacity.byNumber(ref);
        if (found.isEmpty() || capacity.remaining(found.get()) == 0) {
            fallThroughNotes.put(payment.id(), found.isEmpty()
                    ? "Invoice reference " + ref + " in narration matches no open invoice. "
                    : "Invoice reference " + ref + " in narration, but that invoice is already fully allocated. ");
            return Optional.empty();
        }
        InvoiceView invoice = found.get();
        long amount = payment.unallocatedPaise();
        long outstanding = capacity.remaining(invoice);

        double confidence;
        long allocate;
        String reason;
        if (amount == outstanding) {
            confidence = REFERENCE_EXACT;
            allocate = amount;
            reason = "Invoice reference " + ref + " in narration, amount matches exactly";
        } else if (amount < outstanding) {
            confidence = REFERENCE_PARTIAL;
            allocate = amount;
            reason = "Invoice reference " + ref + " in narration; partial payment: " + formatPaise(amount)
                    + " of " + formatPaise(outstanding) + " outstanding, " + formatPaise(outstanding - amount)
                    + " still due";
        } else {
            confidence = REFERENCE_OVERPAID;
            allocate = outstanding;
            reason = "Invoice reference " + ref + " in narration, but the payment of " + formatPaise(amount)
                    + " is more than the " + formatPaise(outstanding) + " outstanding; " + formatPaise(outstanding)
                    + " allocated and " + formatPaise(amount - outstanding) + " overpaid left unallocated";
        }
        return propose(payment, invoice, allocate, MatchType.REFERENCE, confidence, reason, capacity, policy);
    }

    // ---------------------------------------------------------------- pass 2

    private Optional<MatchProposal> exactAmountMatch(PaymentView payment, Capacity capacity, MatchingPolicy policy,
                                                     String prefix) {
        long amount = payment.unallocatedPaise();
        List<InvoiceView> candidates = capacity.openWithRemaining(amount);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String amountText = formatPaise(amount);
        boolean payerNamed = hasText(payment.payerName());

        if (candidates.size() == 1) {
            InvoiceView invoice = candidates.get(0);
            double confidence;
            String reason;
            if (!payerNamed || !hasText(invoice.customerName())) {
                confidence = AMOUNT_NO_NAME;
                reason = "Amount " + amountText + " matches invoice " + invoice.invoiceNumber()
                        + " exactly; no payer name to confirm it is from " + invoice.customerName();
            } else if (isSameName(payment, invoice)) {
                confidence = AMOUNT_NAME_MATCH;
                reason = "Amount " + amountText + " matches invoice " + invoice.invoiceNumber()
                        + " exactly and the payer name matches customer " + invoice.customerName();
            } else {
                confidence = AMOUNT_NAME_MISMATCH;
                reason = "Amount " + amountText + " matches invoice " + invoice.invoiceNumber()
                        + " exactly, but payer \"" + payment.payerName() + "\" does not match customer \""
                        + invoice.customerName() + "\"";
            }
            return propose(payment, invoice, amount, MatchType.EXACT_AMOUNT, confidence, prefix + reason,
                    capacity, policy);
        }

        List<InvoiceView> nameMatches = payerNamed
                ? candidates.stream().filter(inv -> isSameName(payment, inv)).toList()
                : List.of();
        if (nameMatches.size() == 1) {
            InvoiceView invoice = nameMatches.get(0);
            String reason = candidates.size() + " open invoices have exactly " + amountText
                    + " outstanding; the payer name matches only " + invoice.invoiceNumber()
                    + " (" + invoice.customerName() + ")";
            return propose(payment, invoice, amount, MatchType.EXACT_AMOUNT, AMOUNT_TIE_ONE_NAME_MATCH,
                    prefix + reason, capacity, policy);
        }

        InvoiceView pick = pickByIssueDate(candidates, payment);
        boolean issuedBefore = !pick.issueDate().isAfter(payment.receivedDate());
        String reason = candidates.size() + " open invoices are tied at exactly " + amountText
                + " outstanding and the payer name does not single one out; suggested " + pick.invoiceNumber()
                + " (" + pick.customerName() + ", issued " + pick.issueDate() + ")"
                + (issuedBefore ? ", the latest issued on or before the payment date"
                                : ", the earliest issued, as none were issued before the payment date");
        return propose(payment, pick, amount, MatchType.EXACT_AMOUNT, AMOUNT_TIE, prefix + reason, capacity, policy);
    }

    /** Latest issue date on or before the payment date (ties: lowest id); if none, the earliest issued. */
    private static InvoiceView pickByIssueDate(List<InvoiceView> candidates, PaymentView payment) {
        Comparator<InvoiceView> byIssueThenId =
                Comparator.comparing(InvoiceView::issueDate).thenComparing(InvoiceView::id, Comparator.reverseOrder());
        return candidates.stream()
                .filter(inv -> !inv.issueDate().isAfter(payment.receivedDate()))
                .max(byIssueThenId)
                .orElseGet(() -> candidates.stream()
                        .min(Comparator.comparing(InvoiceView::issueDate).thenComparingLong(InvoiceView::id))
                        .orElseThrow());
    }

    // ---------------------------------------------------------------- helpers

    private Optional<MatchProposal> propose(PaymentView payment, InvoiceView invoice, long allocate, MatchType type,
                                            double confidence, String reason, Capacity capacity,
                                            MatchingPolicy policy) {
        return policy.decide(confidence).map(decision -> {
            capacity.consume(invoice, allocate);
            return new MatchProposal(payment.id(), List.of(new AllocationProposal(invoice.id(), allocate)),
                    type, confidence, decision, reason);
        });
    }

    private boolean isSameName(PaymentView payment, InvoiceView invoice) {
        return nameMatcher.similarity(payment.payerName(), invoice.customerName()) >= 1.0;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** Remaining outstanding per invoice for this run; invoices with nothing outstanding are not open. */
    private static final class Capacity {
        private final Map<Long, InvoiceView> byId = new LinkedHashMap<>();
        private final Map<String, InvoiceView> byNumber = new HashMap<>();
        private final Map<Long, Long> remaining = new HashMap<>();

        Capacity(List<InvoiceView> invoices) {
            invoices.stream()
                    .filter(inv -> inv.outstandingPaise() > 0)
                    .sorted(Comparator.comparingLong(InvoiceView::id))
                    .forEach(inv -> {
                        if (byId.putIfAbsent(inv.id(), inv) == null) {
                            byNumber.putIfAbsent(inv.invoiceNumber(), inv);
                            remaining.put(inv.id(), inv.outstandingPaise());
                        }
                    });
        }

        long remaining(InvoiceView invoice) {
            return remaining.get(invoice.id());
        }

        Optional<InvoiceView> byNumber(String number) {
            return Optional.ofNullable(byNumber.get(number));
        }

        /** Open invoices (in id order) whose remaining outstanding equals {@code amount}. */
        List<InvoiceView> openWithRemaining(long amount) {
            return byId.values().stream().filter(inv -> remaining(inv) == amount).toList();
        }

        void consume(InvoiceView invoice, long amount) {
            long left = remaining(invoice) - amount;
            if (left < 0) {
                throw new IllegalStateException("Over-allocation of invoice " + invoice.id());
            }
            remaining.put(invoice.id(), left);
        }
    }
}
