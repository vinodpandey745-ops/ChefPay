package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * A system-generated alert row (low stock, an order/item cancelled, etc.) backing the
 * Alerts/Notification inbox screen - Round 8. {@code category} is a free string rather than an
 * enum (e.g. "LOW_STOCK", "ORDER_CANCELLED") - same "free-text for now, structured later" call
 * {@link OrderItem#getModifiersSummary()}'s javadoc makes, since the set of categories will likely
 * grow as more of the app raises alerts. {@code referenceId} is whatever this notification is
 * about (an {@code Order} id, an {@link InventoryItem} id, ...) - deliberately untyped/no FK since
 * it can point at more than one entity type.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Notification extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false, length = 1000)
    private String message;

    /** Id of whatever this notification is about (an Order, an InventoryItem, ...); null if it's not about a specific record.
     * Needs the same explicit CHAR(36) mapping every other non-PK UUID field in this codebase uses
     * (see BaseEntity.id / AuditLog.userId etc.) - without it, Hibernate 6 assumes PostgreSQL's
     * native "uuid" column type, but this column is CHAR(36) (V10 migration, for portability with
     * SQLite/MySQL), so schema-validation fails with "found [bpchar], but expecting [uuid]". */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID referenceId;

    /** Mapped to the {@code is_read} column, not {@code read} - "read" is a reserved word in some
     * SQL dialects (e.g. MySQL's {@code LOCK TABLES ... READ}) and this project targets both
     * PostgreSQL and MySQL (see the migration files' portability notes). */
    @Builder.Default
    @Column(name = "is_read", nullable = false)
    private boolean read = false;
}
