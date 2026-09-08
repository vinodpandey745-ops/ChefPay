package com.chefpay.core.repository;

import com.chefpay.core.domain.SubscriptionPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, UUID> {

    /** "Recent Payments" list on the restaurant's own Subscription screen - newest first. */
    List<SubscriptionPayment> findByBranchIdOrderByCreatedAtDesc(UUID branchId);

    /** {@code RazorpayWebhookController}'s lookup key: Razorpay's webhook payload carries only the
     * gateway's own order id, not this row's own primary key. */
    Optional<SubscriptionPayment> findByGatewayOrderId(String gatewayOrderId);
}
