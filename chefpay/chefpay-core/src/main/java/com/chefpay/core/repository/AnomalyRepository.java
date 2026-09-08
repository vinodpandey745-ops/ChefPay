package com.chefpay.core.repository;

import com.chefpay.core.domain.Anomaly;
import com.chefpay.core.domain.AnomalySeverity;
import com.chefpay.core.domain.AnomalyStatus;
import com.chefpay.core.domain.FraudRuleCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AnomalyRepository extends JpaRepository<Anomaly, UUID> {

    List<Anomaly> findByBusinessDateOrderBySeverityDescDetectedAtDesc(LocalDate businessDate);

    List<Anomaly> findByBusinessDateAndStatusAndSeverityIn(LocalDate businessDate, AnomalyStatus status, List<AnomalySeverity> severities);

    long countByBusinessDateAndStatusAndSeverityIn(LocalDate businessDate, AnomalyStatus status, List<AnomalySeverity> severities);

    /** Backs MANAGER_PIN_OVERUSE's per-approver count for a business date (only the resolved
     * override actions this rule cares about are ever tagged with a rule code at write time, see
     * {@code FraudRuleEngineService}). */
    List<Anomaly> findByBusinessDateAndRuleCodeAndInvolvedUserId(LocalDate businessDate, FraudRuleCode ruleCode, UUID involvedUserId);

    List<Anomaly> findByEodSessionId(UUID eodSessionId);

    /** Round 14 (F3.4) - backs the rule-precision/manager-feedback report, aggregated in-memory by
     * {@code RulePrecisionService} rather than a DB-side GROUP BY, since the set of {@link
     * com.chefpay.core.domain.FraudRuleCode} values is small and fixed. */
    List<Anomaly> findByBusinessDateBetween(java.time.LocalDate from, java.time.LocalDate to);

    /** Round 14 (F4.2) - escalation candidates: still unreviewed and old enough per {@code
     * Restaurant#getCriticalAlertEscalationMinutes()}. Filtered further in Java (only High/Critical,
     * only past the threshold) rather than a bespoke query per severity/threshold combination. */
    List<Anomaly> findByStatus(com.chefpay.core.domain.AnomalyStatus status);

    /** Final round - data-retention auto-purge candidate query: only ever {@code RESOLVED}
     * anomalies (never {@code UNREVIEWED} - a manager hasn't looked at it yet - and never {@code
     * ESCALATED}, since a CONFIRMED_THEFT resolution always sets status to ESCALATED per {@code
     * EodService.resolveAnomaly}, meaning a RESOLVED anomaly can never be a confirmed-theft case
     * anyway) that were detected before the cutoff are eligible for purge. */
    List<Anomaly> findByStatusAndDetectedAtBefore(AnomalyStatus status, java.time.LocalDateTime cutoff);
}
