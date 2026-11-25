package com.plutus.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByStatusIn(Collection<PaymentStatus> statuses);

    /** Payments with money left to allocate (UNMATCHED or PARTIALLY_ALLOCATED). */
    default List<Payment> findUnreconciled() {
        return findByStatusIn(EnumSet.of(PaymentStatus.UNMATCHED, PaymentStatus.PARTIALLY_ALLOCATED));
    }
}
