package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only audit trail (requirement §23). Deliberately NOT a {@link BaseEntity} - audit rows
 * are immutable, so they get their own id generation without the mutable version/updatedAt
 * columns that would suggest they can change after the fact.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID deviceId;

    @Column(nullable = false)
    private String entityType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID entityId;

    @Column(nullable = false)
    private String action;

    // @Column(columnDefinition = "TEXT") is required alongside @Lob: on PostgreSQL, Hibernate 6's
    // default JDBC type resolution for a bare @Lob String expects the legacy "oid" large-object
    // type, but our Flyway migrations (correctly, and for SQLite/MySQL portability) create these
    // columns as plain TEXT. Without this, Hibernate's schema validator throws
    // "Schema-validation: wrong column type... found [text], but expecting [oid]" at startup
    // against a real Postgres database (this never showed up against the SQLite dev profile,
    // since ddl-auto=update there doesn't validate column types).
    @Lob
    @Column(columnDefinition = "TEXT")
    private String oldValue;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String newValue;

    private String reason;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    private String correlationId;

    // ---- Round 13 (AI Backbone Addendum F1.3): tamper-evident hash chain ----

    /** Strictly increasing, gap-free - see {@link AuditChainState#getLastSeq()}'s javadoc for why
     * this exists instead of ordering the chain by {@link #timestamp}. Nullable only so existing
     * rows from before Round 13 (written before this column existed) don't need a backfill to stay
     * valid - {@code AuditIntegrityService} treats the chain as starting fresh at the first row
     * that actually has a {@link #chainSeq}, and says so plainly rather than pretending pre-Round-13
     * history was ever hash-chained. */
    private Long chainSeq;

    /** Hex SHA-256 of ({@link #prevHash} + this row's canonical payload string), computed by
     * {@code AuditService.record}. */
    @Column(length = 64)
    private String entryHash;

    @Column(length = 64)
    private String prevHash;

    /**
     * Defensive, near-zero-cost backstop for NFR-3 ("the application's database role has no
     * UPDATE/DELETE grant on that table"): {@code AuditLogRepository} exposes no update/delete
     * method today and every call site only ever builds-and-saves a brand-new row (see
     * {@code AuditService.record}), so this should never actually fire against legitimate code -
     * it exists so a future accidental {@code auditLogRepository.save(existingEntity)} on a
     * reloaded row fails loudly instead of silently rewriting history. This is an
     * application-level guard, not the DB-level GRANT/REVOKE NFR-3 describes - see the Round 13
     * report for why the actual DB-role hardening is shipped as an optional, manually-applied DBA
     * script per database engine rather than an automatic migration.
     */
    @PreUpdate
    void blockUpdate() {
        throw new UnsupportedOperationException(
                "AuditLog rows are append-only and can never be updated (id=" + id + ").");
    }

    @PreRemove
    void blockDelete() {
        throw new UnsupportedOperationException(
                "AuditLog rows are append-only and can never be deleted (id=" + id + ").");
    }
}
