package com.chefpay.server.eod;

import com.chefpay.core.domain.*;
import com.chefpay.core.repository.*;
import com.chefpay.core.service.AuditService;
import com.chefpay.core.service.UserAccountService;
import com.chefpay.plugin.api.GlSyncAdapter;
import com.chefpay.server.auth.BiometricAuthService;
import com.chefpay.server.billing.BillingDtos;
import com.chefpay.server.billing.BillingService;
import com.chefpay.server.billing.EmailReceiptService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.fraud.FraudRuleEngineService;
import com.chefpay.server.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The guided EOD wizard - Ingest -&gt; Blind Cash Count -&gt; Fraud &amp; Audit Review -&gt;
 * Finalize &amp; GL Sync (AI Backbone Addendum F1.1-F1.7). See {@code EodSession}'s javadoc for
 * the restaurant-wide (not per-branch) scoping decision this round makes.
 *
 * <p><b>Concurrency note:</b> unlike {@code Order}/{@code Payment} (edited by many terminals at
 * once, hence every mutation there checks {@code expectedVersion}), an EOD session is a
 * once-a-day, effectively single-operator workflow - this service does not enforce optimistic
 * locking on {@link EodSession}/{@link CashCount}/{@link Anomaly} mutations (though every entity
 * still carries a {@code version} column from {@link com.chefpay.core.domain.BaseEntity} for a
 * future round to tighten if multi-manager-concurrent-EOD ever becomes a real scenario).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EodService {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_DATE;

    private final EodSessionRepository eodSessionRepository;
    private final ChannelIngestionRepository channelIngestionRepository;
    private final CashCountRepository cashCountRepository;
    private final AggregatorSettlementRepository aggregatorSettlementRepository;
    private final AnomalyRepository anomalyRepository;
    private final RuleConfigRepository ruleConfigRepository;
    private final RestaurantRepository restaurantRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final AppUserRepository appUserRepository;
    private final UserAccountService userAccountService;
    private final BiometricAuthService biometricAuthService;
    private final AuditService auditService;
    private final FraudRuleEngineService fraudRuleEngineService;
    private final ZReportPdfBuilder zReportPdfBuilder;
    private final BillingService billingService;
    private final EmailReceiptService emailReceiptService;
    private final List<GlSyncAdapter> glSyncAdapters;
    private final InventoryService inventoryService;

    // ---- Ingest ----

    @Transactional
    public EodSession startSession(LocalDate businessDate, UUID actorUserId) {
        Optional<EodSession> existing = eodSessionRepository.findByBusinessDate(businessDate);
        if (existing.isPresent()) {
            return existing.get();
        }

        Restaurant restaurant = currentRestaurant();
        EodSession session = eodSessionRepository.save(EodSession.builder()
                .businessDate(businessDate)
                .status(EodSessionStatus.INGESTING)
                .openingFloat(restaurant.getDefaultOpeningFloat())
                .startedBy(actorUserId == null ? null : appUserRepository.getReferenceById(actorUserId))
                .build());

        LocalDateTime start = businessDate.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        BigDecimal posTotal = paymentRepository.findByVoidedFalseAndReceivedAtBetween(start, end).stream()
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        channelIngestionRepository.save(ChannelIngestion.builder()
                .eodSession(session)
                .channelType(ChannelType.POS)
                .channelName("POS")
                .status(ChannelIngestionStatus.SUCCESS)
                .amountReported(posTotal)
                .ingestedAt(LocalDateTime.now())
                .build());

        channelIngestionRepository.save(ChannelIngestion.builder()
                .eodSession(session)
                .channelType(ChannelType.AGGREGATOR)
                .channelName("Zomato")
                .status(restaurant.isOnlineOrderZomatoEnabled() ? ChannelIngestionStatus.PENDING : ChannelIngestionStatus.NOT_CONFIGURED)
                .build());
        channelIngestionRepository.save(ChannelIngestion.builder()
                .eodSession(session)
                .channelType(ChannelType.AGGREGATOR)
                .channelName("Swiggy")
                .status(restaurant.isOnlineOrderSwiggyEnabled() ? ChannelIngestionStatus.PENDING : ChannelIngestionStatus.NOT_CONFIGURED)
                .build());

        auditService.record(actorUserId, null, "EodSession", session.getId(), "EOD_SESSION_STARTED", null,
                businessDate.toString(), null, CorrelationIdHolder.get());
        return session;
    }

    @Transactional(readOnly = true)
    public EodSession getSession(UUID sessionId) {
        return eodSessionRepository.findById(sessionId).orElseThrow(() -> ApiException.notFound("EOD session not found"));
    }

    @Transactional(readOnly = true)
    public List<ChannelIngestion> listChannels(UUID sessionId) {
        return channelIngestionRepository.findByEodSessionIdOrderByChannelTypeAsc(sessionId);
    }

    @Transactional(readOnly = true)
    public Optional<AggregatorSettlement> getSettlement(UUID channelIngestionId) {
        return aggregatorSettlementRepository.findByChannelIngestionId(channelIngestionId);
    }

    /** Read-only lookup for {@code EodController#toSessionDto} - the cash count is otherwise only
     * ever returned as the direct result of {@link #submitCashCount}/{@link #overrideCashCount}, but
     * the session DTO also needs to embed it on a plain {@code GET}. */
    @Transactional(readOnly = true)
    public Optional<CashCount> findCashCount(UUID sessionId) {
        return cashCountRepository.findByEodSessionId(sessionId);
    }

    @Transactional
    public AggregatorSettlement submitAggregatorSettlement(UUID sessionId, UUID channelIngestionId,
                                                            BigDecimal posRecordedTotal, BigDecimal reportedSettlementTotal,
                                                            BigDecimal commissionAmount, String notes, UUID actorUserId) {
        EodSession session = getSession(sessionId);
        ChannelIngestion channel = channelIngestionRepository.findById(channelIngestionId)
                .orElseThrow(() -> ApiException.notFound("Channel not found"));
        if (!channel.getEodSession().getId().equals(sessionId)) {
            throw ApiException.badRequest("CHANNEL_SESSION_MISMATCH", "That channel does not belong to this EOD session.");
        }
        if (channel.getStatus() == ChannelIngestionStatus.NOT_CONFIGURED) {
            throw ApiException.conflict("CHANNEL_NOT_CONFIGURED", channel.getChannelName() + " is not enabled for this restaurant.");
        }

        BigDecimal variancePercent = BigDecimal.ZERO;
        if (posRecordedTotal.compareTo(BigDecimal.ZERO) > 0) {
            variancePercent = posRecordedTotal.subtract(reportedSettlementTotal)
                    .divide(posRecordedTotal, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        Optional<RuleConfig> config = ruleConfigRepository.findByRuleCode(FraudRuleCode.AGGREGATOR_SETTLEMENT_MISMATCH);
        boolean flagged = config.isPresent() && config.get().isEnabled()
                && variancePercent.compareTo(config.get().getThresholdValue()) >= 0;

        AggregatorSettlement settlement = aggregatorSettlementRepository.save(AggregatorSettlement.builder()
                .eodSession(session)
                .channelIngestion(channel)
                .aggregatorName(channel.getChannelName())
                .posRecordedTotal(posRecordedTotal)
                .reportedSettlementTotal(reportedSettlementTotal)
                .commissionAmount(commissionAmount)
                .variancePercent(variancePercent)
                .flagged(flagged)
                .notes(notes)
                .enteredBy(actorUserId == null ? null : appUserRepository.getReferenceById(actorUserId))
                .enteredAt(LocalDateTime.now())
                .build());

        channel.setStatus(ChannelIngestionStatus.SUCCESS);
        channel.setAmountReported(reportedSettlementTotal);
        channel.setIngestedAt(LocalDateTime.now());
        channelIngestionRepository.save(channel);

        if (flagged) {
            RuleConfig cfg = config.get();
            anomalyRepository.save(Anomaly.builder()
                    .businessDate(session.getBusinessDate())
                    .eodSession(session)
                    .ruleCode(FraudRuleCode.AGGREGATOR_SETTLEMENT_MISMATCH)
                    .severity(cfg.getSeverity())
                    .category("Aggregator Reconciliation")
                    .description(channel.getChannelName() + "'s settled amount (" + reportedSettlementTotal
                            + ") is " + variancePercent.setScale(1, RoundingMode.HALF_UP)
                            + "% below the POS-recorded value (" + posRecordedTotal + ") for " + session.getBusinessDate() + ".")
                    .referenceEntityType("AggregatorSettlement")
                    .referenceEntityId(settlement.getId())
                    .amountImpact(posRecordedTotal.subtract(reportedSettlementTotal))
                    .detectedAt(LocalDateTime.now())
                    .build());
        }

        auditService.record(actorUserId, null, "AggregatorSettlement", settlement.getId(), "AGGREGATOR_SETTLEMENT_ENTERED",
                null, reportedSettlementTotal.toPlainString(), notes, CorrelationIdHolder.get());
        return settlement;
    }

    @Transactional
    public EodSession advanceToCashCount(UUID sessionId, UUID actorUserId) {
        EodSession session = getSession(sessionId);
        if (!session.getStatus().canTransitionTo(EodSessionStatus.CASH_COUNT)) {
            throw ApiException.conflict("INVALID_EOD_TRANSITION", "Cannot move to Cash Count from " + session.getStatus() + ".");
        }
        session.setStatus(EodSessionStatus.CASH_COUNT);
        EodSession saved = eodSessionRepository.save(session);
        auditService.record(actorUserId, null, "EodSession", saved.getId(), "EOD_ADVANCED_TO_CASH_COUNT", null, null, null,
                CorrelationIdHolder.get());
        return saved;
    }

    // ---- Blind Cash Count ----

    @Transactional
    public CashCount submitCashCount(UUID sessionId, List<EodDtos.DenominationLine> denominations, String denominationJson,
                                      UUID actorUserId) {
        EodSession session = getSession(sessionId);
        if (session.getStatus() != EodSessionStatus.CASH_COUNT) {
            throw ApiException.conflict("EOD_NOT_IN_CASH_COUNT", "This session is not on the Cash Count step (currently "
                    + session.getStatus() + ").");
        }

        BigDecimal physicalTotal = denominations.stream()
                .map(d -> d.value().multiply(BigDecimal.valueOf(d.count())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BillingDtos.CashSummaryDto cashSummary = billingService.getCashSummary(session.getBusinessDate());
        BigDecimal expectedTotal = session.getOpeningFloat().add(cashSummary.expectedCashInDrawer());
        BigDecimal variance = physicalTotal.subtract(expectedTotal);

        Restaurant restaurant = currentRestaurant();
        CashCountStatus status;
        if (variance.compareTo(BigDecimal.ZERO) == 0) {
            status = CashCountStatus.MATCHED;
        } else if (variance.abs().compareTo(restaurant.getCashVarianceThreshold()) <= 0) {
            status = CashCountStatus.ACCEPTABLE_VARIANCE;
        } else {
            status = CashCountStatus.HIGH_VARIANCE_FLAGGED;
        }

        CashCount cashCount = cashCountRepository.findByEodSessionId(sessionId).orElseGet(() -> CashCount.builder()
                .eodSession(session).build());
        cashCount.setDenominationBreakdownJson(denominationJson);
        cashCount.setPhysicalTotal(physicalTotal);
        cashCount.setExpectedTotal(expectedTotal);
        cashCount.setVariance(variance);
        cashCount.setStatus(status);
        cashCount.setCountedBy(actorUserId == null ? null : appUserRepository.getReferenceById(actorUserId));
        cashCount.setCountedAt(LocalDateTime.now());
        // Resetting any prior override - a resubmitted count (still allowed while status ==
        // CASH_COUNT/REVIEW, before finalize) is a fresh count and must be re-evaluated/re-overridden
        // if it's flagged again, not silently inherit an approval for different numbers.
        cashCount.setOverrideBy(null);
        cashCount.setOverrideReason(null);
        cashCount.setOverriddenAt(null);
        CashCount saved = cashCountRepository.save(cashCount);

        auditService.record(actorUserId, null, "CashCount", saved.getId(), "CASH_COUNT_SUBMITTED", null,
                physicalTotal.toPlainString(), status.name(), CorrelationIdHolder.get());

        if (session.getStatus().canTransitionTo(EodSessionStatus.REVIEW)) {
            session.setStatus(EodSessionStatus.REVIEW);
            eodSessionRepository.save(session);
            try {
                fraudRuleEngineService.runEodBatch(session.getBusinessDate());
            } catch (Exception ex) {
                log.error("EOD batch fraud rules failed for {} - review screen will show only event-time anomalies.",
                        session.getBusinessDate(), ex);
            }
        }
        return saved;
    }

    /** F4.5: PIN first (if provided - unchanged behavior for every existing caller), falling back
     * to a biometric read if a device id was supplied instead. {@link BiometricAuthService}
     * already no-ops (returns empty) when no provider is installed or the restaurant hasn't
     * enabled it, so this never behaves differently from the PIN-only code path that existed
     * before F4.5 unless both a plugin AND the setting are present. */
    private Optional<AppUser> resolveLevel3Approver(String pin, UUID biometricDeviceId) {
        if (pin != null && !pin.isBlank()) {
            return userAccountService.verifyPinForPermission(pin, "EOD_OVERRIDE");
        }
        if (biometricDeviceId != null) {
            return biometricAuthService.verifyForPermission(biometricDeviceId, "EOD_OVERRIDE");
        }
        return Optional.empty();
    }

    @Transactional
    public CashCount overrideCashCount(UUID sessionId, String pin, String reason, UUID biometricDeviceId, UUID actorUserId) {
        EodSession session = getSession(sessionId);
        CashCount cashCount = cashCountRepository.findByEodSessionId(sessionId)
                .orElseThrow(() -> ApiException.notFound("No cash count submitted yet for this session."));
        if (cashCount.getStatus() != CashCountStatus.HIGH_VARIANCE_FLAGGED) {
            throw ApiException.conflict("OVERRIDE_NOT_NEEDED", "This cash count isn't flagged for high variance.");
        }
        AppUser approver = resolveLevel3Approver(pin, biometricDeviceId)
                .orElseThrow(() -> ApiException.forbidden("Incorrect PIN (or no matching biometric read) for a Level-3 (EOD_OVERRIDE) user."));

        cashCount.setOverrideBy(approver);
        cashCount.setOverrideReason(reason);
        cashCount.setOverriddenAt(LocalDateTime.now());
        CashCount saved = cashCountRepository.save(cashCount);

        auditService.record(approver.getId(), null, "CashCount", saved.getId(), "CASH_VARIANCE_OVERRIDE",
                cashCount.getVariance().toPlainString(), null, reason, CorrelationIdHolder.get());
        return saved;
    }

    // ---- Review ----

    @Transactional(readOnly = true)
    public List<Anomaly> listAnomalies(LocalDate businessDate) {
        return anomalyRepository.findByBusinessDateOrderBySeverityDescDetectedAtDesc(businessDate);
    }

    /** F4.3 (Mobile Manager Companion): every currently-open anomaly across ALL business dates,
     * not just one - the existing {@link #listAnomalies(LocalDate)} above is date-scoped, which is
     * right for the desktop EOD Review step (one business date at a time) but wrong for a manager
     * checking their phone, who needs "everything still waiting on me" regardless of which day it
     * was detected. "Open" here means {@link AnomalyStatus#UNREVIEWED} specifically - an
     * {@code ESCALATED} anomaly already has a resolution/reason recorded (see {@link
     * #resolveAnomaly}), it's simply flagged as more serious, not still awaiting a first decision. */
    @Transactional(readOnly = true)
    public List<Anomaly> listUnresolvedAnomalies() {
        return anomalyRepository.findByStatus(AnomalyStatus.UNREVIEWED).stream()
                .sorted(Comparator.comparing(Anomaly::getSeverity).reversed()
                        .thenComparing(Anomaly::getDetectedAt, Comparator.reverseOrder()))
                .toList();
    }

    @Transactional
    public Anomaly resolveAnomaly(UUID anomalyId, AnomalyResolutionType resolutionType, String escalationReasonCode,
                                   String note, UUID actorUserId) {
        Anomaly anomaly = anomalyRepository.findById(anomalyId).orElseThrow(() -> ApiException.notFound("Anomaly not found"));
        if (resolutionType == AnomalyResolutionType.CONFIRMED_THEFT
                && (escalationReasonCode == null || escalationReasonCode.isBlank())) {
            throw ApiException.badRequest("ESCALATION_REASON_REQUIRED", "A reason is required when confirming fraud/escalating.");
        }
        anomaly.setResolutionType(resolutionType);
        anomaly.setEscalationReasonCode(resolutionType == AnomalyResolutionType.CONFIRMED_THEFT ? escalationReasonCode : null);
        anomaly.setResolutionNote(note);
        anomaly.setResolvedBy(actorUserId == null ? null : appUserRepository.getReferenceById(actorUserId));
        anomaly.setResolvedAt(LocalDateTime.now());
        anomaly.setStatus(resolutionType == AnomalyResolutionType.CONFIRMED_THEFT ? AnomalyStatus.ESCALATED : AnomalyStatus.RESOLVED);
        Anomaly saved = anomalyRepository.save(anomaly);

        auditService.record(actorUserId, null, "Anomaly", saved.getId(), "ANOMALY_RESOLVED", null,
                resolutionType.name(), note, CorrelationIdHolder.get());
        return saved;
    }

    // ---- Finalize & GL Sync ----

    @Transactional
    public EodSession finalizeSession(UUID sessionId, String overridePin, String overrideReason,
                                       UUID overrideBiometricDeviceId, UUID actorUserId) {
        EodSession session = getSession(sessionId);
        if (session.getStatus() != EodSessionStatus.REVIEW) {
            throw ApiException.conflict("EOD_NOT_IN_REVIEW", "This session is not on the Review step (currently "
                    + session.getStatus() + ").");
        }

        List<String> blockers = collectFinalizeBlockers(session);
        boolean overrideUsed = false;
        if (!blockers.isEmpty()) {
            AppUser approver = resolveLevel3Approver(overridePin, overrideBiometricDeviceId).orElse(null);
            if (approver == null) {
                throw ApiException.conflict("EOD_FINALIZE_BLOCKED",
                        "Cannot finalize: " + String.join("; ", blockers) + ". A Level-3 PIN override with a reason is required.");
            }
            if (overrideReason == null || overrideReason.isBlank()) {
                throw ApiException.badRequest("OVERRIDE_REASON_REQUIRED", "A reason is required to force-finalize.");
            }
            overrideUsed = true;
        }

        ZReportData data = buildZReportData(session);
        String zReportText = buildZReportText(data);
        byte[] pdfBytes = safeBuildPdf(data);

        session.setZReportText(zReportText);
        session.setZReportPdfBase64(pdfBytes == null ? null : Base64.getEncoder().encodeToString(pdfBytes));
        session.setFinalizedWithOverride(overrideUsed);
        session.setFinalizeOverrideReason(overrideUsed ? overrideReason : null);
        session.setStatus(EodSessionStatus.FINALIZED);
        session.setFinalizedAt(LocalDateTime.now());
        session.setFinalizedBy(actorUserId == null ? null : appUserRepository.getReferenceById(actorUserId));

        syncToGl(session, data);
        emailZReport(session, zReportText);

        EodSession saved = eodSessionRepository.save(session);
        auditService.record(actorUserId, null, "EodSession", saved.getId(),
                overrideUsed ? "EOD_FINALIZED_WITH_OVERRIDE" : "EOD_FINALIZED", null, session.getBusinessDate().toString(),
                overrideUsed ? overrideReason : null, CorrelationIdHolder.get());
        return saved;
    }

    @Transactional(readOnly = true)
    public byte[] getZReportPdfBytes(UUID sessionId) {
        EodSession session = getSession(sessionId);
        if (session.getZReportPdfBase64() == null) {
            throw ApiException.notFound("No Z-Report PDF available - this session hasn't been finalized yet.");
        }
        return Base64.getDecoder().decode(session.getZReportPdfBase64());
    }

    private List<String> collectFinalizeBlockers(EodSession session) {
        List<String> blockers = new ArrayList<>();
        List<ChannelIngestion> channels = channelIngestionRepository.findByEodSessionIdOrderByChannelTypeAsc(session.getId());
        boolean incompleteChannel = channels.stream()
                .anyMatch(c -> c.getStatus() == ChannelIngestionStatus.FAILED || c.getStatus() == ChannelIngestionStatus.PENDING);
        if (incompleteChannel) {
            blockers.add("one or more channels have not finished ingestion");
        }

        cashCountRepository.findByEodSessionId(session.getId()).ifPresent(cc -> {
            if (cc.getStatus() == CashCountStatus.HIGH_VARIANCE_FLAGGED && cc.getOverrideBy() == null) {
                blockers.add("the cash count has an unresolved high variance");
            }
        });

        long unresolvedHighCritical = anomalyRepository.countByBusinessDateAndStatusAndSeverityIn(
                session.getBusinessDate(), AnomalyStatus.UNREVIEWED, List.of(AnomalySeverity.HIGH, AnomalySeverity.CRITICAL));
        if (unresolvedHighCritical > 0) {
            blockers.add(unresolvedHighCritical + " High/Critical anomal" + (unresolvedHighCritical == 1 ? "y is" : "ies are") + " unreviewed");
        }
        return blockers;
    }

    private ZReportData buildZReportData(EodSession session) {
        LocalDate businessDate = session.getBusinessDate();
        LocalDateTime start = businessDate.atStartOfDay();
        LocalDateTime end = start.plusDays(1);

        List<Order> billedOrders = orderRepository.findByBilledAtBetween(start, end);
        BigDecimal grossSales = sum(billedOrders, Order::getSubtotal);
        BigDecimal discountTotal = sum(billedOrders, Order::getDiscountAmount);
        BigDecimal taxTotal = sum(billedOrders, Order::getTaxAmount);
        BigDecimal serviceChargeTotal = sum(billedOrders, Order::getServiceChargeAmount);
        BigDecimal tipTotal = sum(billedOrders, Order::getTipAmount);
        BigDecimal netTotal = sum(billedOrders, Order::getTotalAmount);

        List<Payment> payments = paymentRepository.findByVoidedFalseAndReceivedAtBetween(start, end);
        Map<PaymentMethod, List<Payment>> byMethod = new EnumMap<>(PaymentMethod.class);
        for (Payment p : payments) {
            byMethod.computeIfAbsent(p.getMethod(), k -> new ArrayList<>()).add(p);
        }
        List<ZReportData.TenderLine> tenderBreakdown = byMethod.entrySet().stream()
                .map(e -> new ZReportData.TenderLine(e.getKey().name(),
                        sum(e.getValue(), Payment::getAmount), e.getValue().size()))
                .sorted(Comparator.comparing(ZReportData.TenderLine::amount).reversed())
                .toList();

        CashCount cashCount = cashCountRepository.findByEodSessionId(session.getId()).orElse(null);

        List<Anomaly> anomalies = anomalyRepository.findByBusinessDateOrderBySeverityDescDetectedAtDesc(businessDate);
        long highCritical = anomalies.stream().filter(a -> a.getSeverity() == AnomalySeverity.HIGH || a.getSeverity() == AnomalySeverity.CRITICAL).count();
        long unresolved = anomalies.stream().filter(a -> a.getStatus() == AnomalyStatus.UNREVIEWED).count();
        List<String> summaryLines = anomalies.stream().limit(15)
                .map(a -> "[" + a.getSeverity() + "] " + a.getCategory() + " - " + a.getDescription()
                        + " (" + a.getStatus() + (a.getResolutionType() == null ? "" : ", " + a.getResolutionType()) + ")")
                .toList();

        // Round 14 (F2.2): low-stock items get their own EOD summary section, same list {@code
        // InventoryService#listLowStock} already backs on the Alerts inbox - never blocks
        // finalize either way, purely informational for the manager reviewing the Z-Report.
        // Bistrodesk Phase 2: InventoryService#listLowStock now takes an accessible-branch-ids
        // filter - null keeps this Z-Report section's existing whole-install behavior unchanged
        // (branch-scoping EOD itself is a later phase's concern, not this one's).
        List<String> lowStockLines = inventoryService.listLowStock(null).stream()
                .map(i -> i.getName() + ": " + i.getQuantityOnHand().stripTrailingZeros().toPlainString() + " " + i.getUnit()
                        + " on hand (reorder at " + i.getReorderThreshold().stripTrailingZeros().toPlainString() + " " + i.getUnit() + ")")
                .toList();

        return new ZReportData(
                currentRestaurant().getName(), businessDate, grossSales, discountTotal, taxTotal, serviceChargeTotal,
                tipTotal, netTotal, tenderBreakdown, billedOrders.size(), session.getOpeningFloat(),
                cashCount == null ? null : cashCount.getExpectedTotal(), cashCount == null ? null : cashCount.getPhysicalTotal(),
                cashCount == null ? null : cashCount.getVariance(), cashCount == null ? "NOT SUBMITTED" : cashCount.getStatus().name(),
                anomalies.size(), (int) highCritical, (int) unresolved, summaryLines,
                session.isFinalizedWithOverride(), session.getFinalizeOverrideReason(), lowStockLines);
    }

    private String buildZReportText(ZReportData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(center(data.restaurantName(), 40)).append('\n');
        sb.append(center("END OF DAY REPORT", 40)).append('\n');
        sb.append(center(data.businessDate().format(ISO_DATE), 40)).append('\n');
        sb.append("-".repeat(40)).append('\n');
        sb.append(line("Gross Sales", data.grossSales())).append('\n');
        sb.append(line("Discounts", data.discountTotal().negate())).append('\n');
        sb.append(line("Tax", data.taxTotal())).append('\n');
        sb.append(line("Service Charge", data.serviceChargeTotal())).append('\n');
        sb.append(line("Tips", data.tipTotal())).append('\n');
        sb.append(line("NET TOTAL", data.netTotal())).append('\n');
        sb.append("Orders billed: ").append(data.ordersBilled()).append('\n');
        sb.append("-".repeat(40)).append('\n');
        sb.append("TENDER BREAKDOWN\n");
        for (ZReportData.TenderLine t : data.tenderBreakdown()) {
            sb.append(line(t.method() + " (" + t.count() + ")", t.amount())).append('\n');
        }
        sb.append("-".repeat(40)).append('\n');
        sb.append("CASH DRAWER\n");
        sb.append(line("Opening Float", data.openingFloat())).append('\n');
        if (data.expectedCash() != null) {
            sb.append(line("Expected Cash", data.expectedCash())).append('\n');
            sb.append(line("Physical Count", data.physicalCash())).append('\n');
            sb.append(line("Variance", data.cashVariance())).append('\n');
        }
        sb.append("Status: ").append(data.cashCountStatus()).append('\n');
        sb.append("-".repeat(40)).append('\n');
        sb.append("FRAUD & AUDIT REVIEW\n");
        sb.append(data.anomaliesTotal()).append(" anomalies flagged (").append(data.anomaliesHighCritical())
                .append(" High/Critical), ").append(data.anomaliesUnresolved()).append(" unresolved at finalize.\n");
        for (String s : data.anomalySummaryLines()) {
            sb.append("- ").append(s).append('\n');
        }
        if (!data.lowStockSummaryLines().isEmpty()) {
            sb.append("-".repeat(40)).append('\n');
            sb.append("LOW STOCK (Round 14 F2.2)\n");
            for (String s : data.lowStockSummaryLines()) {
                sb.append("- ").append(s).append('\n');
            }
        }
        if (data.finalizedWithOverride()) {
            sb.append("-".repeat(40)).append('\n');
            sb.append("FINALIZED WITH LEVEL-3 OVERRIDE: ").append(data.finalizeOverrideReason()).append('\n');
        }
        return sb.toString();
    }

    private byte[] safeBuildPdf(ZReportData data) {
        try {
            return zReportPdfBuilder.build(data);
        } catch (Exception ex) {
            log.error("Z-Report PDF generation failed for {} - text report is still available.", data.businessDate(), ex);
            return null;
        }
    }

    private void syncToGl(EodSession session, ZReportData data) {
        if (glSyncAdapters.isEmpty()) {
            session.setGlSyncStatus("NOT_CONFIGURED");
            return;
        }
        try {
            // GlSyncAdapter#syncDay wants netSales "after discount, before tax" as a distinct figure
            // from the grand total - ZReportData has no such field stored directly (its own
            // "netTotal" is actually each order's grand total, tax/service/tip included), so it's
            // derived here rather than reusing netTotal for both parameters.
            java.math.BigDecimal netSalesBeforeTax = data.grossSales().subtract(data.discountTotal());
            String message = glSyncAdapters.get(0).syncDay(session.getBusinessDate().toString(), data.grossSales(),
                    netSalesBeforeTax, data.taxTotal(), data.netTotal());
            session.setGlSyncStatus("SUCCESS");
            session.setGlSyncMessage(message);
        } catch (Exception ex) {
            session.setGlSyncStatus("FAILED");
            session.setGlSyncMessage(ex.getMessage());
            log.error("GL sync failed for EOD session {}.", session.getId(), ex);
        }
    }

    private void emailZReport(EodSession session, String zReportText) {
        Restaurant restaurant = currentRestaurant();
        String recipients = restaurant.getEodZReportRecipientEmails();
        if (recipients == null || recipients.isBlank()) {
            return;
        }
        for (String address : recipients.split(",")) {
            String trimmed = address.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                emailReceiptService.sendDocument(trimmed, "Z-Report - " + session.getBusinessDate(), zReportText);
            } catch (Exception ex) {
                log.error("Could not email Z-Report to {} for session {}.", trimmed, session.getId(), ex);
            }
        }
    }

    private Restaurant currentRestaurant() {
        return restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
    }

    private <T> BigDecimal sum(List<T> items, java.util.function.Function<T, BigDecimal> extractor) {
        return items.stream().map(extractor).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String line(String label, BigDecimal amount) {
        String trimmed = label.length() <= 26 ? label : label.substring(0, 25) + "…";
        return String.format("%-26s %11s", trimmed, amount.setScale(2, RoundingMode.HALF_UP));
    }

    private String center(String text, int width) {
        if (text == null || text.length() >= width) {
            return text == null ? "" : text;
        }
        return " ".repeat((width - text.length()) / 2) + text;
    }
}
