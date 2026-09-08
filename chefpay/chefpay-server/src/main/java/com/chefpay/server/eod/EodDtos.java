package com.chefpay.server.eod;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

    public record SubmitAggregatorSettlementRequest(@NotNull UUID channelIngestionId,
                                                      @NotNull BigDecimal posRecordedTotal,
                                                      @NotNull BigDecimal reportedSettlementTotal,
                                                      BigDecimal commissionAmount, String notes) {
    }

    public record AggregatorSettlementDto(UUID id, UUID channelIngestionId, String aggregatorName,
                                           BigDecimal posRecordedTotal, BigDecimal reportedSettlementTotal,
                                           BigDecimal commissionAmount, BigDecimal variancePercent, boolean flagged,
                                           String notes) {
    }

    /** One denomination line from the JavaFX blind-count grid - see {@code CashCount}'s javadoc for
     * why this is a free-form value/count pair rather than a fixed currency-specific enum. */
    public record DenominationLine(@NotNull BigDecimal value, @NotNull Integer count) {
    }

    public record SubmitCashCountRequest(@NotNull List<DenominationLine> denominations) {
    }

    public record CashCountDto(UUID id, BigDecimal physicalTotal, BigDecimal expectedTotal, BigDecimal variance,
                                String status, String overriddenByName, String overrideReason,
                                LocalDateTime overriddenAt, long version) {
    }

    /** F4.5: {@code pin} is optional here (not {@code @NotNull}) specifically so a terminal with a
     * biometric reader can submit {@code biometricDeviceId} instead - {@code EodService} requires
     * at least one of the two to be present, PIN remaining the mandatory fallback when neither a
     * biometric provider is installed nor {@code Restaurant#isBiometricOverrideEnabled()} is on. */
    public record OverrideCashCountRequest(String pin, @NotNull String reason, UUID biometricDeviceId) {
    }

    public record AnomalyDto(UUID id, LocalDate businessDate, String ruleCode, String severity, String category,
                              String description, String referenceEntityType, UUID referenceEntityId,
                              UUID referenceOrderId, BigDecimal amountImpact, String involvedUserName, String status,
                              LocalDateTime detectedAt, String resolutionType, String escalationReasonCode,
                              String resolutionNote, String resolvedByName, LocalDateTime resolvedAt, long version,
                              String cctvFootageUrl) {
    }

    public record ResolveAnomalyRequest(@NotNull String resolutionType, String escalationReasonCode, String note) {
    }

    /** F4.5: {@code overrideBiometricDeviceId} is an alternative to {@code overridePin} for the
     * force-finalize step-up, same "PIN remains mandatory as the fallback, biometric is additive"
     * framing as {@link OverrideCashCountRequest}. */
    public record FinalizeRequest(String overridePin, String overrideReason, UUID overrideBiometricDeviceId) {
    }

    public record FinalizeResultDto(UUID sessionId, String zReportText, String glSyncStatus, String glSyncMessage) {
    }
}
