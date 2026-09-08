package com.chefpay.core.repository;

import com.chefpay.core.domain.Payment;
import com.chefpay.core.domain.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByOrderIdOrderByReceivedAtAsc(UUID orderId);

    /** Backs the cash-drawer reconciliation in {@code BillingService.getCashSummary}. */
    List<Payment> findByMethodAndVoidedFalseAndReceivedAtBetween(PaymentMethod method, LocalDateTime start, LocalDateTime end);

    /** Backs the Phase 5 dashboard's "today's sales" KPI - every tender method, voided ones excluded. */
    List<Payment> findByVoidedFalseAndReceivedAtBetween(LocalDateTime start, LocalDateTime end);

    /** F3.3 (AI Backbone Addendum peer-baseline anomaly scoring): unlike the query above, this
     * deliberately INCLUDES voided payments - {@code PeerBaselineOutlierRule} needs the voided ones
     * to compute each cashier's void-to-sales ratio in the first place. */
    List<Payment> findByReceivedAtBetween(LocalDateTime start, LocalDateTime end);
}
