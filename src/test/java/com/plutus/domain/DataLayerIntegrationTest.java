package com.plutus.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.plutus.AbstractIntegrationTest;

class DataLayerIntegrationTest extends AbstractIntegrationTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-20T10:15:30Z");

    @Autowired InvoiceRepository invoices;
    @Autowired PaymentRepository payments;
    @Autowired AllocationRepository allocations;
    @Autowired TransactionTemplate tx;

    @Test
    void entitiesRoundTripAndDerivedHelpersWork() {
        Invoice invoice = invoices.saveAndFlush(invoice("INV-001", 150_000));
        Payment payment = payments.saveAndFlush(payment(PaymentSource.UPI, "UTR123", 100_000));

        assertThat(invoice.getId()).isNotNull();
        assertThat(invoice.getVersion()).isZero();
        assertThat(invoice.getCreatedAt()).isNotNull();
        assertThat(invoice.outstandingPaise()).isEqualTo(150_000);
        assertThat(payment.unallocatedPaise()).isEqualTo(100_000);
        assertThat(invoices.findOpen()).extracting(Invoice::getInvoiceNumber).containsExactly("INV-001");
        assertThat(payments.findUnreconciled()).extracting(Payment::getExternalRef).containsExactly("UTR123");
    }

    @Test
    void duplicateSourceAndExternalRefIsRejected() {
        payments.saveAndFlush(payment(PaymentSource.UPI, "UTR-DUP", 50_000));

        assertThatThrownBy(() -> payments.saveAndFlush(payment(PaymentSource.UPI, "UTR-DUP", 50_000)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameExternalRefFromDifferentSourcesIsAllowed() {
        payments.saveAndFlush(payment(PaymentSource.UPI, "REF-1", 50_000));
        payments.saveAndFlush(payment(PaymentSource.PAYPAL, "REF-1", 50_000));

        assertThat(payments.count()).isEqualTo(2);
    }

    @Test
    void zeroAmountPaymentIsRejectedByCheckConstraint() {
        // Raw SQL, because the entity constructor already refuses non-positive amounts;
        // this proves the database enforces it too.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payments (source, external_ref, amount_paise, received_at, status)
                VALUES ('UPI', 'UTR-ZERO', 0, now(), 'UNMATCHED')"""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_amount_paise_check");
    }

    @Test
    void allocatedGreaterThanAmountIsRejected() {
        Payment payment = payment(PaymentSource.BANK_TRANSFER, "BANK-1", 10_000);
        ReflectionTestUtils.setField(payment, "allocatedPaise", 10_001L);

        assertThatThrownBy(() -> payments.saveAndFlush(payment))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payments_allocated_within_amount");

        Invoice invoice = invoice("INV-OVER", 10_000);
        ReflectionTestUtils.setField(invoice, "allocatedPaise", 10_001L);

        assertThatThrownBy(() -> invoices.saveAndFlush(invoice))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_invoices_allocated_within_amount");
    }

    @Test
    void concurrentInvoiceUpdatesTriggerOptimisticLock() {
        Long id = invoices.saveAndFlush(invoice("INV-LOCK", 99_900)).getId();

        // Two separate transactions read the same row (version 0); both copies become detached.
        Invoice first = tx.execute(s -> invoices.findById(id).orElseThrow());
        Invoice second = tx.execute(s -> invoices.findById(id).orElseThrow());

        ReflectionTestUtils.setField(first, "customerName", "Updated by first");
        tx.executeWithoutResult(s -> invoices.save(first)); // version 0 -> 1

        ReflectionTestUtils.setField(second, "customerName", "Updated by second");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> invoices.save(second))) // still at version 0
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Invoice reloaded = invoices.findById(id).orElseThrow();
        assertThat(reloaded.getCustomerName()).isEqualTo("Updated by first");
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void partialUniqueIndexAllowsReproposalOnlyAfterRejection() {
        Invoice invoice = invoices.saveAndFlush(invoice("INV-ALLOC", 50_000));
        Payment payment = payments.saveAndFlush(payment(PaymentSource.UPI, "UTR-ALLOC", 50_000));

        Allocation suggested = allocations.saveAndFlush(allocation(payment, invoice, AllocationStatus.SUGGESTED));

        // Blocked while SUGGESTED.
        assertThatThrownBy(() -> allocations.saveAndFlush(allocation(payment, invoice, AllocationStatus.SUGGESTED)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Blocked while CONFIRMED.
        jdbc.update("UPDATE allocations SET status = 'CONFIRMED', decided_at = now() WHERE id = ?", suggested.getId());
        assertThatThrownBy(() -> allocations.saveAndFlush(allocation(payment, invoice, AllocationStatus.SUGGESTED)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Allowed once the existing one is REJECTED.
        jdbc.update("UPDATE allocations SET status = 'REJECTED' WHERE id = ?", suggested.getId());
        Allocation reproposed = allocations.saveAndFlush(allocation(payment, invoice, AllocationStatus.SUGGESTED));

        assertThat(reproposed.getId()).isNotEqualTo(suggested.getId());
        assertThat(allocations.findByPaymentId(payment.getId())).hasSize(2);
        assertThat(allocations.findByStatus(AllocationStatus.REJECTED)).hasSize(1);
        assertThat(allocations.findByStatus(AllocationStatus.SUGGESTED)).hasSize(1);
    }

    private static Invoice invoice(String number, long amountPaise) {
        return new Invoice(number, "Asha Traders", amountPaise, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
    }

    private static Payment payment(PaymentSource source, String ref, long amountPaise) {
        return new Payment(source, ref, "ASHA TRADERS", "asha@okbank", amountPaise, RECEIVED,
                "UPI/" + ref + "/ASHA TRADERS/INV-001", "INV-001");
    }

    private static Allocation allocation(Payment payment, Invoice invoice, AllocationStatus status) {
        return new Allocation(payment, invoice, 50_000, MatchType.REFERENCE, new BigDecimal("0.990"), status,
                "Narration contains invoice number INV-001");
    }
}
