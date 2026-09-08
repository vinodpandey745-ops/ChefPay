package com.chefpay.server.audit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class AuditDtos {

    private AuditDtos() {
    }

    public record EntryDto(UUID id, UUID userId, String userName, String entityType, UUID entityId, String action,
                            String oldValue, String newValue, String reason, LocalDateTime timestamp) {
    }

    public record PageDto(List<EntryDto> entries, int page, int size, long totalElements, int totalPages) {
    }

    public record BrokenLinkDto(UUID auditLogId, Long chainSeq, LocalDateTime timestamp, String problem) {
    }

    public record IntegrityReportDto(boolean pass, int totalHashedEntries, int totalBrokenLinks,
                                      List<BrokenLinkDto> brokenLinksInRange, LocalDateTime from, LocalDateTime to) {
    }
}
