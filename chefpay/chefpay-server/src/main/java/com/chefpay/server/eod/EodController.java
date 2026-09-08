package com.chefpay.server.eod;

import com.chefpay.core.domain.AggregatorSettlement;
import com.chefpay.core.domain.Anomaly;
import com.chefpay.core.domain.AnomalyResolutionType;
import com.chefpay.core.domain.CashCount;
import com.chefpay.core.domain.ChannelIngestion;
import com.chefpay.core.domain.EodSession;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST surface for the guided EOD wizard (AI Backbone Addendum F1.1-F1.7, Section 9.1's API
 * sketch). Every endpoint requires {@code EOD_MANAGE} - the Level-3 ({@code EOD_OVERRIDE}) step-up
 * is enforced inside {@link EodService} itself via {@code UserAccountService#verifyPinForPermission},
 * not as a separate controller-level authority, since the override is a PIN entered by whoever is
 * running the wizard, not a distinct logged-in role (same reasoning as {@code CashCount#overrideBy}'s
 * javadoc).
 */
@RestController
@RequestMapping("/api/eod")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('EOD_MANAGE')")
public class EodController {

    private final EodService eodService;

    @PostMapping("/sessions")
    public ApiResponse<EodDtos.SessionDto> startSession(@RequestBody EodDtos.StartSessionRequest request,
                                                          @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        LocalDate businessDate = request.businessDate() == null ? LocalDate.now() : request.businessDate();
        return ApiResponse.ok(toSessionDto(eodService.startSession(businessDate, userId(principal))));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<EodDtos.SessionDto> getSession(@PathVariable UUID sessionId) {
        return ApiResponse.ok(toSessionDto(eodService.getSession(sessionId)));
    }

    @GetMapping("/sessions/{sessionId}/channels")
    public ApiResponse<List<EodDtos.ChannelIngestionDto>> listChannels(@PathVariable UUID sessionId) {
        return ApiResponse.ok(eodService.listChannels(sessionId).stream().map(this::toChannelDto).toList());
    }

    @PostMapping("/sessions/{sessionId}/aggregator-settlement")
    public ApiResponse<EodDtos.AggregatorSettlementDto> submitAggregatorSettlement(
            @PathVariable UUID sessionId, @Valid @RequestBody EodDtos.SubmitAggregatorSettlementRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AggregatorSettlement settlement = eodService.submitAggregatorSettlement(sessionId, request.channelIngestionId(),
                request.posRecordedTotal(), request.reportedSettlementTotal(), request.commissionAmount(), request.notes(),
                userId(principal));
        return ApiResponse.ok(toSettlementDto(settlement));
    }

    @PostMapping("/sessions/{sessionId}/advance-to-cash-count")
    public ApiResponse<EodDtos.SessionDto> advanceToCashCount(@PathVariable UUID sessionId,
                                                               @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(toSessionDto(eodService.advanceToCashCount(sessionId, userId(principal))));
    }

    @PostMapping("/sessions/{sessionId}/cash-count")
    public ApiResponse<EodDtos.CashCountDto> submitCashCount(@PathVariable UUID sessionId,
                                                              @Valid @RequestBody EodDtos.SubmitCashCountRequest request,
                                                              @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        String denominationJson = toDenominationJson(request.denominations());
        CashCount saved = eodService.submitCashCount(sessionId, request.denominations(), denominationJson, userId(principal));
        return ApiResponse.ok(toCashCountDto(saved));
    }

    @PostMapping("/sessions/{sessionId}/cash-count/override")
    public ApiResponse<EodDtos.CashCountDto> overrideCashCount(@PathVariable UUID sessionId,
                                                                @Valid @RequestBody EodDtos.OverrideCashCountRequest request,
                                                                @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        CashCount saved = eodService.overrideCashCount(sessionId, request.pin(), request.reason(),
                request.biometricDeviceId(), userId(principal));
        return ApiResponse.ok(toCashCountDto(saved));
    }

    @GetMapping("/sessions/{sessionId}/anomalies")
    public ApiResponse<List<EodDtos.AnomalyDto>> listAnomalies(@PathVariable UUID sessionId) {
        EodSession session = eodService.getSession(sessionId);
        return ApiResponse.ok(eodService.listAnomalies(session.getBusinessDate()).stream().map(this::toAnomalyDto).toList());
    }

    @GetMapping("/anomalies")
    public ApiResponse<List<EodDtos.AnomalyDto>> listAnomaliesByDate(@RequestParam LocalDate businessDate) {
        return ApiResponse.ok(eodService.listAnomalies(businessDate).stream().map(this::toAnomalyDto).toList());
    }

    /** F4.3 (Mobile Manager Companion) - cross-date "everything still open" list, see {@link
     * EodService#listUnresolvedAnomalies()}'s javadoc for why this differs from the date-scoped
     * endpoint above. Same {@code EOD_MANAGE} authority as every other endpoint in this controller;
     * the mobile SPA itself is served from a permitAll static path, but this data endpoint is not. */
    @GetMapping("/anomalies/unresolved")
    public ApiResponse<List<EodDtos.AnomalyDto>> listUnresolvedAnomalies() {
        return ApiResponse.ok(eodService.listUnresolvedAnomalies().stream().map(this::toAnomalyDto).toList());
    }

    @PostMapping("/anomalies/{anomalyId}/resolve")
    public ApiResponse<EodDtos.AnomalyDto> resolveAnomaly(@PathVariable UUID anomalyId,
                                                           @Valid @RequestBody EodDtos.ResolveAnomalyRequest request,
                                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AnomalyResolutionType resolutionType = AnomalyResolutionType.valueOf(request.resolutionType());
        Anomaly saved = eodService.resolveAnomaly(anomalyId, resolutionType, request.escalationReasonCode(), request.note(),
                userId(principal));
        return ApiResponse.ok(toAnomalyDto(saved));
    }

    @PostMapping("/sessions/{sessionId}/finalize")
    public ApiResponse<EodDtos.FinalizeResultDto> finalizeSession(@PathVariable UUID sessionId,
                                                                   @RequestBody(required = false) EodDtos.FinalizeRequest request,
                                                                   @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        EodDtos.FinalizeRequest body = request == null ? new EodDtos.FinalizeRequest(null, null, null) : request;
        EodSession saved = eodService.finalizeSession(sessionId, body.overridePin(), body.overrideReason(),
                body.overrideBiometricDeviceId(), userId(principal));
        return ApiResponse.ok(new EodDtos.FinalizeResultDto(saved.getId(), saved.getZReportText(), saved.getGlSyncStatus(),
                saved.getGlSyncMessage()));
    }

    @GetMapping(value = "/sessions/{sessionId}/z-report.pdf")
    public ResponseEntity<ByteArrayResource> zReportPdf(@PathVariable UUID sessionId) {
        byte[] pdf = eodService.getZReportPdfBytes(sessionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"z-report-" + sessionId + ".pdf\"")
                .body(new ByteArrayResource(pdf));
    }

    // ---- mapping helpers ----

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }

    private String toDenominationJson(List<EodDtos.DenominationLine> lines) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            EodDtos.DenominationLine line = lines.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"value\":").append(line.value().toPlainString())
                    .append(",\"count\":").append(line.count()).append('}');
        }
        return sb.append(']').toString();
    }

    private EodDtos.SessionDto toSessionDto(EodSession session) {
        List<EodDtos.ChannelIngestionDto> channels = eodService.listChannels(session.getId()).stream()
                .map(this::toChannelDto).toList();
        Optional<CashCount> cashCount = eodService.findCashCount(session.getId());
        return new EodDtos.SessionDto(session.getId(), session.getBusinessDate(), session.getStatus().name(),
                session.getOpeningFloat(), channels, cashCount.map(this::toCashCountDto).orElse(null),
                session.isFinalizedWithOverride(), session.getFinalizeOverrideReason(), session.getFinalizedAt(),
                session.getZReportText(), session.getZReportPdfBase64() != null, session.getGlSyncStatus(),
                session.getGlSyncMessage(), session.getVersion());
    }

    private EodDtos.ChannelIngestionDto toChannelDto(ChannelIngestion channel) {
        AggregatorSettlement settlement = eodService.getSettlement(channel.getId()).orElse(null);
        return new EodDtos.ChannelIngestionDto(channel.getId(), channel.getChannelType().name(), channel.getChannelName(),
                channel.getStatus().name(), channel.getAmountReported(), channel.getMessage(), channel.getIngestedAt(),
                settlement == null ? null : toSettlementDto(settlement));
    }

    private EodDtos.AggregatorSettlementDto toSettlementDto(AggregatorSettlement settlement) {
        return new EodDtos.AggregatorSettlementDto(settlement.getId(), settlement.getChannelIngestion().getId(),
                settlement.getAggregatorName(), settlement.getPosRecordedTotal(), settlement.getReportedSettlementTotal(),
                settlement.getCommissionAmount(), settlement.getVariancePercent(), settlement.isFlagged(), settlement.getNotes());
    }

    private EodDtos.CashCountDto toCashCountDto(CashCount cashCount) {
        return new EodDtos.CashCountDto(cashCount.getId(), cashCount.getPhysicalTotal(), cashCount.getExpectedTotal(),
                cashCount.getVariance(), cashCount.getStatus().name(),
                cashCount.getOverrideBy() == null ? null : cashCount.getOverrideBy().getDisplayName(),
                cashCount.getOverrideReason(), cashCount.getOverriddenAt(), cashCount.getVersion());
    }

    private EodDtos.AnomalyDto toAnomalyDto(Anomaly anomaly) {
        return new EodDtos.AnomalyDto(anomaly.getId(), anomaly.getBusinessDate(), anomaly.getRuleCode().name(),
                anomaly.getSeverity().name(), anomaly.getCategory(), anomaly.getDescription(), anomaly.getReferenceEntityType(),
                anomaly.getReferenceEntityId(), anomaly.getReferenceOrderId(), anomaly.getAmountImpact(),
                anomaly.getInvolvedUser() == null ? null : anomaly.getInvolvedUser().getDisplayName(), anomaly.getStatus().name(),
                anomaly.getDetectedAt(), anomaly.getResolutionType() == null ? null : anomaly.getResolutionType().name(),
                anomaly.getEscalationReasonCode(), anomaly.getResolutionNote(),
                anomaly.getResolvedBy() == null ? null : anomaly.getResolvedBy().getDisplayName(), anomaly.getResolvedAt(),
                anomaly.getVersion(), anomaly.getCctvFootageUrl());
    }
}
