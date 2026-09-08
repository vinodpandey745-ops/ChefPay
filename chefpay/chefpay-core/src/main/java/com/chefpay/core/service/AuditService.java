package com.chefpay.core.service;

import com.chefpay.core.domain.AuditChainState;
import com.chefpay.core.domain.AuditLog;
import com.chefpay.core.repository.AuditChainStateRepository;
import com.chefpay.core.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Single write path for the append-only audit trail (requirement §23). Any service that mutates
 * something worth auditing calls {@link #record} rather than writing {@code AuditLogRepository}
 * directly, so the shape of an audit entry stays consistent across modules.
 *
 * <p>Round 13 (AI Backbone Addendum F1.3) adds a SHA-256 hash chain: every entry's
 * {@code entryHash} covers the previous entry's hash plus this entry's own payload, so any
 * retroactive edit or deletion of a historical row is detectable by re-walking the chain (see
 * {@code AuditIntegrityService}). The chain head ({@link AuditChainState}, a single locked row) is
 * read-modify-written inside this same {@code @Transactional} method with
 * {@code REQUIRED} propagation (joining the caller's ambient transaction) rather than
 * {@code REQUIRES_NEW} - identical reasoning to {@code NumberGeneratorService}'s javadoc: a second,
 * independent connection committing while the caller's own transaction/connection is still open is
 * a structural deadlock on SQLite, so this deliberately holds the lock for the length of the
 * caller's transaction instead.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final String CHAIN_STATE_ID = "SINGLETON";

    private final AuditLogRepository auditLogRepository;
    private final AuditChainStateRepository auditChainStateRepository;

    @Transactional
    public void record(UUID userId, UUID deviceId, String entityType, UUID entityId, String action,
                        String oldValue, String newValue, String reason, String correlationId) {
        LocalDateTime timestamp = LocalDateTime.now();

        AuditChainState chainState = auditChainStateRepository.findForUpdate(CHAIN_STATE_ID)
                .orElseGet(() -> auditChainStateRepository.save(
                        new AuditChainState(CHAIN_STATE_ID, "0".repeat(64), 0L)));

        String payload = canonicalPayload(userId, deviceId, entityType, entityId, action, oldValue, newValue,
                reason, correlationId, timestamp);
        String prevHash = chainState.getLastHash();
        String entryHash = sha256Hex(prevHash + payload);
        long seq = chainState.getLastSeq() + 1;

        AuditLog log = AuditLog.builder()
                .userId(userId)
                .deviceId(deviceId)
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(reason)
                .correlationId(correlationId)
                .timestamp(timestamp)
                .chainSeq(seq)
                .prevHash(prevHash)
                .entryHash(entryHash)
                .build();
        auditLogRepository.save(log);

        chainState.setLastHash(entryHash);
        chainState.setLastSeq(seq);
        auditChainStateRepository.save(chainState);
    }

    /** Deterministic string form of one entry's content - anything that changes what an entry
     * "means" must be included here, or a tampered field wouldn't actually change the hash. Field
     * separators are a control character unlikely to appear in any of these values, purely to avoid
     * two different field combinations concatenating to the same string (e.g. "ab"+"c" vs "a"+"bc"). */
    private String canonicalPayload(UUID userId, UUID deviceId, String entityType, UUID entityId, String action,
                                     String oldValue, String newValue, String reason, String correlationId,
                                     LocalDateTime timestamp) {
        String sep = "";
        return String.join(sep,
                str(userId), str(deviceId), str(entityType), str(entityId), str(action),
                str(oldValue), str(newValue), str(reason), str(correlationId), str(timestamp));
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm on every JVM - this can never actually happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
