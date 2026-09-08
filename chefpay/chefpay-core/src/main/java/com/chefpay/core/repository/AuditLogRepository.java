package com.chefpay.core.repository;

import com.chefpay.core.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByEntityTypeAndEntityIdOrderByTimestampDesc(String entityType, UUID entityId);

    /** Round 10: backs the AI Audit Anomaly Flagging feature's scan window - newest first, same as
     * every other audit read here. */
    List<AuditLog> findByTimestampBetweenOrderByTimestampDesc(LocalDateTime from, LocalDateTime to);

    /** Backs the Phase 5 audit trail viewer ({@code AuditController}) - newest first, optionally filtered. */
    Page<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    Page<AuditLog> findByEntityTypeOrderByTimestampDesc(String entityType, Pageable pageable);

    Page<AuditLog> findByUserIdOrderByTimestampDesc(UUID userId, Pageable pageable);

    Page<AuditLog> findByEntityTypeAndUserIdOrderByTimestampDesc(String entityType, UUID userId, Pageable pageable);

    /** Round 13 (F1.3): the full hash-chain walk order for {@code AuditIntegrityService}. Rows
     * written before Round 13 have a null {@code chainSeq} and are excluded - see
     * {@code AuditLog#chainSeq}'s javadoc for why that's a deliberate "chain starts fresh here",
     * not a bug. At very large audit volumes a full-chain walk on every verification call is the
     * known scaling limit of this Round 13 implementation - see the Round 13 report. */
    List<AuditLog> findByChainSeqIsNotNullOrderByChainSeqAsc();

    /** Round 13 (F1.5): backs NO_SALE_FREQUENCY's rolling-window count and MANAGER_PIN_OVERUSE's
     * per-approver, per-business-date count. */
    List<AuditLog> findByUserIdAndActionAndTimestampBetween(UUID userId, String action, LocalDateTime from, LocalDateTime to);

    List<AuditLog> findByActionAndTimestampBetween(String action, LocalDateTime from, LocalDateTime to);
}
