package com.plutus.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Money received, one row per statement line. Amounts are paise. */
@Entity
@Table(name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uq_payments_source_external_ref",
                columnNames = {"source", "external_ref"}))
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private PaymentSource source;

    @Column(name = "external_ref", nullable = false, length = 100)
    private String externalRef;

    @Column(name = "payer_name", length = 200)
    private String payerName;

    @Column(name = "payer_handle", length = 200)
    private String payerHandle;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "allocated_paise", nullable = false)
    private long allocatedPaise;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "narration", columnDefinition = "text")
    private String narration;

    @Column(name = "reference_hint", length = 100)
    private String referenceHint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Payment() {
        // for JPA
    }

    public Payment(PaymentSource source, String externalRef, String payerName, String payerHandle,
                   long amountPaise, Instant receivedAt, String narration, String referenceHint) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive: " + amountPaise);
        }
        this.source = Objects.requireNonNull(source, "source");
        this.externalRef = Objects.requireNonNull(externalRef, "externalRef");
        this.payerName = payerName;
        this.payerHandle = payerHandle;
        this.amountPaise = amountPaise;
        this.allocatedPaise = 0;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.narration = narration;
        this.referenceHint = referenceHint;
        this.status = PaymentStatus.UNMATCHED;
    }

    /** Portion of this payment not yet allocated to any invoice, in paise. */
    public long unallocatedPaise() {
        return amountPaise - allocatedPaise;
    }

    public Long getId() {
        return id;
    }

    public PaymentSource getSource() {
        return source;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public String getPayerName() {
        return payerName;
    }

    public String getPayerHandle() {
        return payerHandle;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public long getAllocatedPaise() {
        return allocatedPaise;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getNarration() {
        return narration;
    }

    public String getReferenceHint() {
        return referenceHint;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
