package com.chefpay.server.retention;

import com.chefpay.core.domain.Anomaly;
import com.chefpay.core.domain.AnomalyStatus;
import com.chefpay.core.domain.EodSession;
import com.chefpay.core.domain.EodSessionStatus;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.Payment;
import com.chefpay.core.domain.PaymentStatus;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.domain.SupplierInvoice;
import com.chefpay.core.domain.SupplierInvoiceStatus;
import com.chefpay.core.repository.AnomalyRepository;
import com.chefpay.core.repository.EodSessionRepository;
import com.chefpay.core.repository.NotificationLogRepository;
import com.chefpay.core.repository.OrderRepository;
import com.chefpay.core.repository.PaymentRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.core.repository.SupplierInvoiceRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.common.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Final round - "data-retention auto-purge." Keeps a long-lived SQLite deployment's file size
 * bounded over months/years of continuous operation, per the user's own framing: "if sqlite use
 * the systems space or filesystem to save record then we may set a certain limit to maintain data
 * till atleast one month and then auto purge... any DB issue should not hamper or crash the
 * application."
 *
 * <p><b>This is deliberately independent of {@code SqliteDataSourceConfig}</b> - that fix addresses
 * a schema-SHAPE ceiling (SQLite's compound-SELECT term limit, tripped by the number of
 * tables/columns Hibernate introspects), which does not shrink no matter how many rows are deleted.
 * This service instead bounds the schema's DATA volume over time, which is a real and separate
 * concern for any database file that grows forever - unrelated to what tripped the original crash,
 * but exactly what the user asked to be handled "accordingly" once that crash was explained.
 *
 * <p><b>Scope - what this purges, and why each carve-out below is deliberate:</b> the user was
 * shown, and explicitly re-confirmed after an explicit warning, "Operational logs + old
 * orders/payments" as the retention scope (financial records included). Within that scope, this
 * service still refuses to touch a few things on its own judgment, layered on top of - not instead
 * of - the user's explicit choice:
 * <ul>
 *   <li><b>{@code audit_log} is never purged</b> - F1.3 defines it as an immutable, tamper-evident
 *   trail; a retention job that erased its own evidence would defeat the point of having one.</li>
 *   <li><b>{@code Anomaly} rows are only purged when {@code RESOLVED}</b> - never {@code UNREVIEWED}
 *   (nobody has looked at it yet) and never {@code ESCALATED} (a live/confirmed loss-prevention
 *   case). A {@code RESOLVED} anomaly can never have been a {@code CONFIRMED_THEFT} resolution
 *   either, since {@code EodService.resolveAnomaly} always routes that combination to
 *   {@code ESCALATED} instead.</li>
 *   <li><b>{@code Order}/{@code Payment} rows are only purged when fully settled AND their business
 *   date's {@link EodSession} is {@code FINALIZED}</b> - an order still being disputed, or a day
 *   that hasn't closed its books yet, is never a purge candidate. If no {@code EodSession} exists
 *   at all for that business date, the order is skipped entirely rather than guessed at. The {@code
 *   EodSession}/Z-Report row itself is never deleted - only the underlying {@code Order}/{@code
 *   OrderItem}/{@code Payment} detail rows - so the day's summary/Z-Report survives even after its
 *   line-item detail has aged out, partially honoring this codebase's pre-existing "a finalized day
 *   is closed for good" principle (see {@code EodSessionStatus}'s own javadoc) even while following
 *   the user's explicit instruction to reclaim the detail rows' space.</li>
 *   <li><b>{@code SupplierInvoice} raw images are only cleared when {@code CONFIRMED} or {@code
 *   REJECTED}</b> - never {@code PENDING_REVIEW}, since a manager may still need to see the photo
 *   to finish reviewing it. Only the (large) base64 image/OCR text is cleared; the invoice row and
 *   its line items stay, since those still feed historical costing reports.</li>
 * </ul>
 *
 * <p>Master switch: {@link Restaurant#isAutoPurgeEnabled()} defaults to {@code false} - nothing is
 * ever purged until an owner/admin opts in via Settings. Every sub-step is individually wrapped so
 * one failing category never blocks the others, matching the "any DB issue should not hamper or
 * crash the application" instruction - and the whole run is itself invoked defensively by {@link
 * DataRetentionScheduler}, so a purge failure can never crash the app either.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataRetentionService {

    private final RestaurantRepository restaurantRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final AnomalyRepository anomalyRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final EodSessionRepository eodSessionRepository;
    private final AuditService auditService;

    /**
     * Entry point, called once a day by {@link DataRetentionScheduler}. No-ops entirely (fast,
     * cheap check) unless the restaurant has explicitly opted in via {@link
     * Restaurant#isAutoPurgeEnabled()}.
     *
     * <p>Deliberately NOT itself {@code @Transactional}: each repository call below already gets
     * its own transaction from Spring Data JPA's default per-method transactional behavior. Wrapping
     * all four purge categories (and every order inside the fourth) in one shared outer transaction
     * would defeat this method's own "one bad category/row should never block the rest" goal - once
     * any repository call throws inside an ambient Spring-managed transaction, that transaction is
     * marked rollback-only at the AOP/proxy boundary regardless of whether the exception is then
     * caught in plain Java, so every later write in the same run would silently fail to commit too.
     * Leaving each call to open and close its own transaction keeps a failure exactly as contained
     * as the {@code safelyPurge}/per-order try/catch blocks below already look like they guarantee.
     */
    public void runPurge() {
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst().orElse(null);
        if (restaurant == null || !restaurant.isAutoPurgeEnabled()) {
            return;
        }

        int retentionDays = restaurant.getDataRetentionDays() > 0 ? restaurant.getDataRetentionDays() : 30;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        long notificationLogsPurged = safelyPurge("notification logs", () -> purgeNotificationLogs(cutoff));
        long anomaliesPurged = safelyPurge("resolved anomalies", () -> purgeResolvedAnomalies(cutoff));
        long invoiceImagesPurged = safelyPurge("stale supplier-invoice images", () -> purgeStaleInvoiceImages(cutoff));
        long ordersPurged = safelyPurge("old settled orders/payments", () -> purgeOldSettledOrders(cutoff));

        long totalAffected = notificationLogsPurged + anomaliesPurged + invoiceImagesPurged + ordersPurged;
        if (totalAffected == 0) {
            log.info("Data-retention purge ran (cutoff {}, retentionDays {}) - nothing eligible.", cutoff, retentionDays);
            return;
        }

        log.info("Data-retention purge complete: {} notification logs, {} anomalies, {} invoice images, "
                        + "{} orders removed (cutoff {}).",
                notificationLogsPurged, anomaliesPurged, invoiceImagesPurged, ordersPurged, cutoff);

        // A single tamper-evident summary entry survives even though the underlying rows don't -
        // the FACT and SCOPE of what was purged is permanently retained in the audit chain. Wrapped
        // defensively too: a failure here (e.g. the audit chain's own lock row briefly contended)
        // must never make an otherwise-successful purge look like it crashed the run.
        try {
            String summary = String.format(
                    "notificationLogs=%d, resolvedAnomalies=%d, staleInvoiceImages=%d, settledOrders=%d, cutoff=%s, retentionDays=%d",
                    notificationLogsPurged, anomaliesPurged, invoiceImagesPurged, ordersPurged, cutoff, retentionDays);
            auditService.record(null, null, "DataRetention", restaurant.getId(), "AUTO_PURGE",
                    null, summary, "Scheduled data-retention auto-purge", CorrelationIdHolder.get());
        } catch (Exception ex) {
            log.error("Data-retention: purge succeeded but writing its audit summary entry failed.", ex);
        }
    }

    /** High-volume, low-stakes delivery logs - eligible purely by age, no status check needed. */
    long purgeNotificationLogs(LocalDateTime cutoff) {
        return notificationLogRepository.deleteByAttemptedAtBefore(cutoff);
    }

    /** Only RESOLVED anomalies (never UNREVIEWED/ESCALATED - see class javadoc). */
    long purgeResolvedAnomalies(LocalDateTime cutoff) {
        List<Anomaly> candidates = anomalyRepository.findByStatusAndDetectedAtBefore(AnomalyStatus.RESOLVED, cutoff);
        if (candidates.isEmpty()) {
            return 0;
        }
        anomalyRepository.deleteAll(candidates);
        return candidates.size();
    }

    /** Clears only the large raw image/OCR-text payload of CONFIRMED/REJECTED invoices - the
     * invoice row and its parsed line items are kept (still feed historical costing), only the
     * bulky base64 photo and raw OCR text are nulled out once a manager's review of them is long
     * done. PENDING_REVIEW invoices are never touched, at any age. */
    long purgeStaleInvoiceImages(LocalDateTime cutoff) {
        List<SupplierInvoice> confirmed = supplierInvoiceRepository.findByStatusOrderByCreatedAtDesc(SupplierInvoiceStatus.CONFIRMED);
        List<SupplierInvoice> rejected = supplierInvoiceRepository.findByStatusOrderByCreatedAtDesc(SupplierInvoiceStatus.REJECTED);

        long cleared = 0;
        for (SupplierInvoice invoice : confirmed) {
            if (clearInvoiceImageIfStale(invoice, cutoff)) {
                cleared++;
            }
        }
        for (SupplierInvoice invoice : rejected) {
            if (clearInvoiceImageIfStale(invoice, cutoff)) {
                cleared++;
            }
        }
        return cleared;
    }

    private boolean clearInvoiceImageIfStale(SupplierInvoice invoice, LocalDateTime cutoff) {
        if (invoice.getRawImageBase64() == null && invoice.getExtractedRawText() == null) {
            return false;
        }
        if (invoice.getCreatedAt() == null || invoice.getCreatedAt().isAfter(cutoff)) {
            return false;
        }
        invoice.setRawImageBase64(null);
        invoice.setExtractedRawText(null);
        supplierInvoiceRepository.save(invoice);
        return true;
    }

    /** Fully-paid orders billed before the cutoff, but ONLY when their business date's EodSession
     * is FINALIZED - skipped entirely (never guessed at) if no EodSession exists yet for that date.
     * Payments are deleted first (no cascade from Order), then the Order itself (which cascades to
     * OrderItem via existing orphanRemoval). Each order is purged in its own try/catch so one bad
     * row never aborts the rest of the batch - matching this feature's "never crash the app"
     * mandate. The EodSession/Z-Report row itself is never touched. */
    long purgeOldSettledOrders(LocalDateTime cutoff) {
        List<Order> candidates = orderRepository.findByBilledAtBeforeAndPaymentStatus(cutoff, PaymentStatus.PAID);
        long purged = 0;
        for (Order order : candidates) {
            try {
                if (isBusinessDateFinalized(order)) {
                    List<Payment> payments = paymentRepository.findByOrderIdOrderByReceivedAtAsc(order.getId());
                    if (!payments.isEmpty()) {
                        paymentRepository.deleteAll(payments);
                    }
                    orderRepository.delete(order);
                    purged++;
                }
            } catch (Exception ex) {
                log.error("Data-retention: failed to purge order {} - skipping it, continuing with the rest of the batch.",
                        order.getId(), ex);
            }
        }
        return purged;
    }

    private boolean isBusinessDateFinalized(Order order) {
        if (order.getBilledAt() == null) {
            return false;
        }
        Optional<EodSession> session = eodSessionRepository.findByBusinessDate(order.getBilledAt().toLocalDate());
        return session.isPresent() && session.get().getStatus() == EodSessionStatus.FINALIZED;
    }

    /** Wraps one purge category so a failure there never blocks the others or propagates up to
     * crash the scheduled job (and, transitively, the app). */
    private long safelyPurge(String label, java.util.function.LongSupplier action) {
        try {
            return action.getAsLong();
        } catch (Exception ex) {
            log.error("Data-retention: '{}' purge step failed - skipping this category for this run.", label, ex);
            return 0;
        }
    }
}
