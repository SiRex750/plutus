package com.plutus.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {

    List<Allocation> findByStatus(AllocationStatus status);

    List<Allocation> findByPaymentId(Long paymentId);
}
