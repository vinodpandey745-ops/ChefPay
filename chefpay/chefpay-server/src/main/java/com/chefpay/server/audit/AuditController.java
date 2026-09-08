package com.chefpay.server.audit;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.AuditLog;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.AuditLogRepository;
import com.chefpay.core.service.AuditIntegrityService;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only view over the append-only trail {@code AuditService} (chefpay-core) already writes to
 * on every audited mutation (auth events, order/billing/inventory changes, etc.) - Phase 5's Audit
 * slice (ARCHITECTURE.md §13). Deliberately no write endpoints here: the log has exactly one writer
 * ({@code AuditService.record}), this controller only ever reads it back.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository appUserRepository;
    private final AuditIntegrityService auditIntegrityService;

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    public ApiResponse<AuditDtos.PageDto> list(@RequestParam(required = false) String entityType,
                                                @RequestParam(required = false) UUID userId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "timestamp"));
        Page<AuditLog> result;
        if (entityType != null && userId != null) {
            result = auditLogRepository.findByEntityTypeAndUserIdOrderByTimestampDesc(entityType, userId, pageRequest);
        } else if (entityType != null) {
            result = auditLogRepository.findByEntityTypeOrderByTimestampDesc(entityType, pageRequest);
        } else if (userId != null) {
            result = auditLogRepository.findByUserIdOrderByTimestampDesc(userId, pageRequest);
        } else {
            result = auditLogRepository.findAllByOrderByTimestampDesc(pageRequest);
        }

        Map<UUID, String> nameCache = new HashMap<>();
        var entries = result.getContent().stream().map(log -> toEntryDto(log, nameCache)).toList();
        return ApiResponse.ok(new AuditDtos.PageDto(entries, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    /** AI Backbone Addendum F1.3 acceptance criterion: "a background integrity job can walk the
     * hash chain for a given date range and report PASS/FAIL". Exposed here (not a new controller)
     * since it's a read-only view over the same audit trail this controller already serves. */
    @GetMapping("/integrity")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    public ApiResponse<AuditDtos.IntegrityReportDto> checkIntegrity(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        AuditIntegrityService.IntegrityReport report = auditIntegrityService.verifyChain(from, to);
        var brokenLinks = report.brokenLinksInRange().stream()
                .map(b -> new AuditDtos.BrokenLinkDto(b.auditLogId(), b.chainSeq(), b.timestamp(), b.problem()))
                .toList();
        return ApiResponse.ok(new AuditDtos.IntegrityReportDto(report.pass(), report.totalHashedEntries(),
                report.totalBrokenLinks(), brokenLinks, report.from(), report.to()));
    }

    private AuditDtos.EntryDto toEntryDto(AuditLog log, Map<UUID, String> nameCache) {
        String userName = log.getUserId() == null ? "system" : nameCache.computeIfAbsent(log.getUserId(), this::resolveUserName);
        return new AuditDtos.EntryDto(log.getId(), log.getUserId(), userName, log.getEntityType(), log.getEntityId(),
                log.getAction(), log.getOldValue(), log.getNewValue(), log.getReason(), log.getTimestamp());
    }

    private String resolveUserName(UUID userId) {
        return appUserRepository.findById(userId).map(AppUser::getDisplayName).orElse("unknown user");
    }
}
