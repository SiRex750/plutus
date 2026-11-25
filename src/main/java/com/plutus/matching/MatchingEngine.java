package com.plutus.matching;

import static com.plutus.common.Money.formatPaise;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.plutus.domain.MatchType;

/**
 * Proposes payment → invoice matches. Pure Java: works on immutable views, never touches the database.
 *
 * <p>Runs three passes over all payments, strongest evidence first, so a weak match can never take an
 * invoice a stronger match needs:
 * <ol>
 *   <li><b>Reference</b>: the payment's invoice reference names an open invoice.</li>
 *   <li><b>Exact amount</b>: an open invoice whose remaining outstanding equals the payment amount,
 *       with the payer name (graded similarity) confirming or disambiguating.</li>
 *   <li><b>Fuzzy</b>: the payer name resembles a customer, weighted with how well amount and date fit.
 *       Always a suggestion for a human to confirm, never auto-confirmed.</li>
 * </ol>
 * Each invoice's remaining outstanding is tracked across the whole run, so proposals never over-allocate.
 * Payments are processed by (receivedDate, id) and invoices by id, so output is deterministic.
 */
public final class MatchingEngine {

    static final double REFERENCE_EXACT = 1.00;
    static final double REFERENCE_PARTIAL = 0.95;
    static final double REFERENCE_OVERPAID = 0.70;

    static final double AMOUNT_NAME_MATCH = 0.92;
    static final double AMOUNT_NAME_SIMILAR = 0.80;
    static final double AMOUNT_NO_NAME = 0.75;
    static final double AMOUNT_NAME_MISMATCH = 0.55;
    static final double AMOUNT_TIE_ONE_NAME_MATCH = 0.90;
    static final double AMOUNT_TIE = 0.50;

    /** Name score at or above which names count as "the same party". */
    static final double NAME_STRONG = 0.90;
    /** Name score at or above which names "look similar"; also the minimum for any fuzzy candidate. */
    static final double NAME_SIMILAR = 0.70;

    static final double FUZZY_NAME_WEIGHT = 0.5;
    static final double FUZZY_AMOUNT_WEIGHT = 0.3;
    static final double FUZZY_DATE_WEIGHT = 0.2;
    /** Fuzzy confidence never reaches the default auto-confirm threshold. */
    static final double FUZZY_MAX_CONFIDENCE = 0.89;
    static final int DEFAULT_TERMS_DAYS = 30;
    static final int GRACE_DAYS = 30;
    static final int DECAY_DAYS = 60;

    private static final Comparator<PaymentView> PAYMENT_ORDER =
            Comparator.comparing(PaymentView::receivedDate).thenComparingLong(PaymentView::id);

    private final NameMatcher nameMatcher;

    /** Uses {@link FuzzyNameMatcher}. */
    public MatchingEngine() {
        this(new FuzzyNameMatcher());
    }

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
        Map<Long, String> notes = new HashMap<>();

        // Pass 1: reference matches claim their invoices first.
        for (PaymentView payment : ordered) {
            referenceMatch(payment, capacity, policy, notes).ifPresent(p -> proposals.put(payment.id(), p));
        }
        // Pass 2: exact amount, only for payments still unmatched, only against remaining capacity.
        for (PaymentView payment : ordered) {
            if (!proposals.containsKey(payment.id())) {
                exactAmountMatch(payment, capacity, policy, notes.getOrDefault(payment.id(), ""))
                        .ifPresent(p -> proposals.put(payment.id(), p));
            }
        }
        // Pass 3: fuzzy, for whatever is left.
        for (PaymentView payment : ordered) {
            if (!proposals.containsKey(payment.id())) {
                fuzzyMatch(payment, capacity, policy, notes.getOrDefault(payment.id(), ""))
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

    // ---------------------------------------------------------------- pass 1: reference

    private Optional<MatchProposal> referenceMatch(PaymentView payment, Capacity capacity, MatchingPolicy policy,
                                                   Map<Long, String> notes) {
        String ref = payment.referenceHint();
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        Optional<InvoiceView> found = capacity.byNumber(ref);
        if (found.isEmpty() || capacity.remaining(found.get()) == 0) {
            notes.put(payment.id(), found.isEmpty()
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
        return propose(payment, invoice, allocate, MatchType.REFERENCE, confidence, reason, capacity, policy, false);
    }

    // ---------------------------------------------------------------- pass 2: exact amount

    private Optional<MatchProposal> exactAmountMatch(PaymentView payment, Capacity capacity, MatchingPolicy policy,
                                                     String prefix) {
        long amount = payment.unallocatedPaise();
        List<InvoiceView> candidates = capacity.openWithRemaining(amount);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String amountText = formatPaise(amount);

        if (candidates.size() == 1) {
            InvoiceView invoice = candidates.get(0);
            double name = nameScore(payment, invoice);
            String lead = "Amount " + amountText + " matches invoice " + invoice.invoiceNumber() + " exactly";
            double confidence;
            String reason;
            if (!NameMatcher.hasInfo(name)) {
                confidence = AMOUNT_NO_NAME;
                reason = lead + "; no payer name to confirm it is from " + invoice.customerName();
            } else if (name >= NAME_STRONG) {
                confidence = AMOUNT_NAME_MATCH;
                String evidence = evidence(payment, invoice);
                reason = lead + " and the payer name matches customer " + invoice.customerName()
                        + (evidence.isEmpty() ? "" : " (" + evidence + ")");
            } else if (name >= NAME_SIMILAR) {
                confidence = AMOUNT_NAME_SIMILAR;
                reason = lead + " and payer " + payerLabel(payment) + " looks similar to customer \""
                        + invoice.customerName() + "\" (" + similarityText(payment, invoice, name) + ")";
            } else {
                confidence = AMOUNT_NAME_MISMATCH;
                reason = lead + ", but payer " + payerLabel(payment) + " does not match customer \""
                        + invoice.customerName() + "\"";
            }
            return propose(payment, invoice, amount, MatchType.EXACT_AMOUNT, confidence, prefix + reason,
                    capacity, policy, false);
        }

        Map<Long, Double> scores = new HashMap<>();
        candidates.forEach(inv -> scores.put(inv.id(), nameScore(payment, inv)));

        List<InvoiceView> strong = candidates.stream().filter(inv -> scores.get(inv.id()) >= NAME_STRONG).toList();
        if (strong.size() == 1) {
            InvoiceView invoice = strong.get(0);
            String reason = candidates.size() + " open invoices have exactly " + amountText
                    + " outstanding; the payer name matches only " + invoice.invoiceNumber()
                    + " (" + invoice.customerName() + ")";
            return propose(payment, invoice, amount, MatchType.EXACT_AMOUNT, AMOUNT_TIE_ONE_NAME_MATCH,
                    prefix + reason, capacity, policy, false);
        }
        if (strong.isEmpty()) {
            List<InvoiceView> similar = candidates.stream()
                    .filter(inv -> scores.get(inv.id()) >= NAME_SIMILAR).toList();
            if (similar.size() == 1) {
                InvoiceView invoice = similar.get(0);
                String reason = candidates.size() + " open invoices have exactly " + amountText
                        + " outstanding; payer " + payerLabel(payment) + " looks similar only to customer \""
                        + invoice.customerName() + "\" on " + invoice.invoiceNumber() + " ("
                        + similarityText(payment, invoice, scores.get(invoice.id())) + ")";
                return propose(payment, invoice, amount, MatchType.EXACT_AMOUNT, AMOUNT_NAME_SIMILAR,
                        prefix + reason, capacity, policy, false);
            }
        }

        // Genuine tie: prefer the best name score, then the invoice most likely being paid by date.
        double bestName = candidates.stream().mapToDouble(inv -> scores.get(inv.id())).max().orElse(NameMatcher.NO_INFO);
        List<InvoiceView> best = candidates.stream().filter(inv -> scores.get(inv.id()) == bestName).toList();
        InvoiceView pick = pickByIssueDate(best, payment);
        boolean issuedBefore = !pick.issueDate().isAfter(payment.receivedDate());
        String reason = candidates.size() + " open invoices are tied at exactly " + amountText
                + " outstanding and the payer name does not single one out; suggested " + pick.invoiceNumber()
                + " (" + pick.customerName() + ", issued " + pick.issueDate() + ")"
                + (issuedBefore ? ", the latest issued on or before the payment date"
                                : ", the earliest issued, as none were issued before the payment date");
        return propose(payment, pick, amount, MatchType.EXACT_AMOUNT, AMOUNT_TIE, prefix + reason, capacity, policy,
                false);
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

    // ---------------------------------------------------------------- pass 3: fuzzy

    private record FuzzyCandidate(InvoiceView invoice, double nameScore, double amountScore, double dateScore,
                                  double score) {
    }

    private Optional<MatchProposal> fuzzyMatch(PaymentView payment, Capacity capacity, MatchingPolicy policy,
                                               String prefix) {
        long amount = payment.unallocatedPaise();
        Optional<FuzzyCandidate> best = capacity.open().stream()
                .filter(inv -> amount <= capacity.remaining(inv)) // overpayments are never fuzzy-matched
                .map(inv -> {
                    double name = nameScore(payment, inv);
                    if (!NameMatcher.hasInfo(name) || name < NAME_SIMILAR) {
                        return null; // fuzzy always needs a name signal
                    }
                    double amountScore = amountScore(amount, capacity.remaining(inv));
                    double dateScore = dateScore(payment.receivedDate(), inv);
                    return new FuzzyCandidate(inv, name, amountScore, dateScore,
                            fuzzyScore(name, amountScore, dateScore));
                })
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(FuzzyCandidate::score).reversed()
                        .thenComparing(Comparator.comparingDouble(FuzzyCandidate::nameScore).reversed())
                        .thenComparing(c -> c.invoice().issueDate())
                        .thenComparingLong(c -> c.invoice().id()));
        if (best.isEmpty()) {
            return Optional.empty();
        }
        FuzzyCandidate c = best.get();
        InvoiceView invoice = c.invoice();
        long outstanding = capacity.remaining(invoice);
        long allocate = Math.min(amount, outstanding);

        String evidence = evidence(payment, invoice);
        String reason = "Payer " + payerLabel(payment) + " looks like customer \"" + invoice.customerName() + "\" on "
                + invoice.invoiceNumber() + (evidence.isEmpty() ? "" : " (" + evidence + ")") + "; "
                + (amount == outstanding
                        ? "amount " + formatPaise(amount) + " equals the outstanding balance"
                        : "amount " + formatPaise(amount) + " is less than the " + formatPaise(outstanding)
                                + " outstanding (possible instalment)")
                + "; " + dateText(payment.receivedDate(), invoice);
        double confidence = Math.min(c.score(), FUZZY_MAX_CONFIDENCE);
        return propose(payment, invoice, allocate, MatchType.FUZZY, confidence, prefix + reason, capacity, policy,
                true);
    }

    static double fuzzyScore(double nameScore, double amountScore, double dateScore) {
        return FUZZY_NAME_WEIGHT * nameScore + FUZZY_AMOUNT_WEIGHT * amountScore + FUZZY_DATE_WEIGHT * dateScore;
    }

    static double amountScore(long payment, long remaining) {
        if (payment == remaining) {
            return 1.0;
        }
        return payment < remaining ? 0.6 : 0.0;
    }

    /**
     * 1.0 inside the expected payment window [issue date, (due date or issue + 30 days) + 30 days];
     * then decays linearly to 0 over the next 60 days; 0.3 if paid before the invoice was issued.
     */
    static double dateScore(LocalDate paid, InvoiceView invoice) {
        if (paid.isBefore(invoice.issueDate())) {
            return 0.3;
        }
        LocalDate windowEnd = windowEnd(invoice);
        if (!paid.isAfter(windowEnd)) {
            return 1.0;
        }
        long late = ChronoUnit.DAYS.between(windowEnd, paid);
        return Math.max(0.0, 1.0 - (double) late / DECAY_DAYS);
    }

    private static LocalDate windowEnd(InvoiceView invoice) {
        LocalDate due = invoice.dueDate() != null ? invoice.dueDate() : invoice.issueDate().plusDays(DEFAULT_TERMS_DAYS);
        return due.plusDays(GRACE_DAYS);
    }

    private static String dateText(LocalDate paid, InvoiceView invoice) {
        if (paid.isBefore(invoice.issueDate())) {
            return "but it was paid on " + paid + ", before the invoice date " + invoice.issueDate();
        }
        LocalDate windowEnd = windowEnd(invoice);
        if (!paid.isAfter(windowEnd)) {
            return "paid on " + paid + ", within the invoice's payment window";
        }
        return "paid on " + paid + ", " + ChronoUnit.DAYS.between(windowEnd, paid)
                + " days after the expected payment window";
    }

    // ---------------------------------------------------------------- helpers

    private Optional<MatchProposal> propose(PaymentView payment, InvoiceView invoice, long allocate, MatchType type,
                                            double confidence, String reason, Capacity capacity,
                                            MatchingPolicy policy, boolean alwaysSuggest) {
        return policy.decide(confidence).map(decision -> {
            capacity.consume(invoice, allocate);
            Decision finalDecision = alwaysSuggest ? Decision.SUGGEST : decision;
            return new MatchProposal(payment.id(), List.of(new AllocationProposal(invoice.id(), allocate)),
                    type, confidence, finalDecision, reason);
        });
    }

    private double nameScore(PaymentView payment, InvoiceView invoice) {
        return nameMatcher.similarityWithHandle(payment.payerName(), payment.payerHandle(), invoice.customerName());
    }

    private String evidence(PaymentView payment, InvoiceView invoice) {
        return nameMatcher.evidence(payment.payerName(), payment.payerHandle(), invoice.customerName());
    }

    private String similarityText(PaymentView payment, InvoiceView invoice, double score) {
        String evidence = evidence(payment, invoice);
        return "name similarity " + String.format(Locale.ROOT, "%.2f", score)
                + (evidence.isEmpty() ? "" : ": " + evidence);
    }

    private static String payerLabel(PaymentView payment) {
        if (payment.payerName() != null && !payment.payerName().isBlank()) {
            return "\"" + payment.payerName() + "\"";
        }
        return payment.payerHandle() != null ? "with UPI handle " + payment.payerHandle() : "(unnamed)";
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

        /** Invoices (in id order) with something still outstanding. */
        List<InvoiceView> open() {
            return byId.values().stream().filter(inv -> remaining(inv) > 0).toList();
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
