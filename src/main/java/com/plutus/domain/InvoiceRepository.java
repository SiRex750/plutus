package com.plutus.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByStatusIn(Collection<InvoiceStatus> statuses);

    /** Invoices that can still receive payments (UNPAID or PARTIALLY_PAID). */
    default List<Invoice> findOpen() {
        return findByStatusIn(EnumSet.of(InvoiceStatus.UNPAID, InvoiceStatus.PARTIALLY_PAID));
    }
}
