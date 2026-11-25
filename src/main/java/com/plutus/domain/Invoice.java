package com.plutus.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** An invoice the merchant has issued. Amounts are paise. */
@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 64)
    private String invoiceNumber;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "allocated_paise", nullable = false)
    private long allocatedPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Invoice() {
        // for JPA
    }

    public Invoice(String invoiceNumber, String customerName, long amountPaise,
                   LocalDate issueDate, LocalDate dueDate) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Invoice amount must be positive: " + amountPaise);
        }
        this.invoiceNumber = Objects.requireNonNull(invoiceNumber, "invoiceNumber");
        this.customerName = Objects.requireNonNull(customerName, "customerName");
        this.amountPaise = amountPaise;
        this.allocatedPaise = 0;
        this.status = InvoiceStatus.UNPAID;
        this.issueDate = Objects.requireNonNull(issueDate, "issueDate");
        this.dueDate = dueDate;
    }

    /** Amount still owed on this invoice, in paise. */
    public long outstandingPaise() {
        return amountPaise - allocatedPaise;
    }

    public Long getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public long getAllocatedPaise() {
        return allocatedPaise;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
