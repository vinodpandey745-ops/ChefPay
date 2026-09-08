package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Client-side mirror of {@code com.chefpay.server.eod.EodDtos} - see that class for field meaning. */
public final class EodDtos {

    private EodDtos() {
    }

    public record StartSessionRequest(LocalDate businessDate) {
    }

    public record SessionDto(UUID id, LocalDate businessDate, String status, BigDecimal openingFloat,
                              List<ChannelIngestionDto> channels, CashCountDto cashCount,
                              boolean finalizedWithOverride, String finalizeOverrideReason, LocalDateTime finalizedAt,
                              String zReportText, boolean zReportPdfAvailable, String glSyncStatus, String glSyncMessage,
                              long version) {
    }

    public record ChannelIngestionDto(UUID id, String channelType, String channelName, String status,
                                       BigDecimal amountReported, String message, LocalDateTime ingestedAt,
                                       AggregatorSettlementDto settlement) {
    }

    public record SubmitAggregatorSettlementRequest(UUID channelIngestionId, BigDecimal posRecordedTotal,
                                                      BigDecimal reportedSettlementTotal, BigDecimal commissionAmount,
                                                      String notes) {
    }

    public record AggregatorSettlementDto(UUID id, UUID channelIngestionId, String aggregatorName,
                                           BigDecimal posRecordedTotal, BigDecimal reportedSettlementTotal,
                                           BigDecimal commissionAmount, BigDecimal variancePercent, boolean flagged,
                                           String notes) {
    }

    public record DenominationLine(BigDecimal value, Integer count) {
    }

    public record SubmitCashCountRequest(List<DenominationLine> denominations) {
    }

    public record CashCountDto(UUID id, BigDecimal physicalTotal, BigDecimal expectedTotal, BigDecimal variance,
                                String status, String overriddenByName, String overrideReason,
                                LocalDateTime overriddenAt, long version) {
    }

    /** {@code biometricDeviceId} (F4.5) is an alternative to {@code pin} - always sent {@code null}
     * from this desktop client today, since ChefPay ships no biometric hardware/driver wiring for
     * the JavaFX client yet (the plugin interface exists server-side for a future integration to
     * fill in - see {@code com.chefpay.plugin.api.BiometricAuthProvider}'s javadoc). */
    public record OverrideCashCountRequest(String pin, String reason, UUID biometricDeviceId) {
    }

    public record AnomalyDto(UUID id, LocalDate businessDate, String ruleCode, String severity, String category,
                              String description, String referenceEntityType, UUID referenceEntityId,
                              UUID referenceOrderId, BigDecimal amountImpact, String involvedUserName, String status,
                              LocalDateTime detectedAt, String resolutionType, String escalationReasonCode,
                              String resolutionNote, String resolvedByName, LocalDateTime resolvedAt, long version,
                              String cctvFootageUrl) {
    }

    public record ResolveAnomalyRequest(String resolutionType, String escalationReasonCode, String note) {
    }

    /** See {@link OverrideCashCountRequest}'s javadoc - {@code overrideBiometricDeviceId} is
     * always {@code null} from this desktop client today. */
    public record FinalizeRequest(String overridePin, String overrideReason, UUID overrideBiometricDeviceId) {
    }

    public record FinalizeResultDto(UUID sessionId, String zReportText, String glSyncStatus, String glSyncMessage) {
    }
}
