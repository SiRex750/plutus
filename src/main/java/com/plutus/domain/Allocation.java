package com.plutus.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** "This much of this payment pays this invoice", plus how and how confidently it was matched. */
@Entity
@Table(name = "allocations")
public class Allocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private MatchType matchType;

    @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AllocationStatus status;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected Allocation() {
        // for JPA
    }

    public Allocation(Payment payment, Invoice invoice, long amountPaise, MatchType matchType,
                      BigDecimal confidence, AllocationStatus status, String reason) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Allocation amount must be positive: " + amountPaise);
        }
        this.payment = Objects.requireNonNull(payment, "payment");
        this.invoice = Objects.requireNonNull(invoice, "invoice");
        this.amountPaise = amountPaise;
        this.matchType = Objects.requireNonNull(matchType, "matchType");
        this.confidence = Objects.requireNonNull(confidence, "confidence");
        this.status = Objects.requireNonNull(status, "status");
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public AllocationStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
