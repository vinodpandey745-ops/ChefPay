package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Common identity/audit/concurrency columns shared by every entity that can be independently
 * fetched, mutated by more than one client, and needs a stable id exposed over REST.
 *
 * <p>UUID primary keys (per requirement §60 "Use UUID primary keys where appropriate") avoid
 * leaking sequential row counts through the API and make client-generated idempotent creates
 * possible later. The {@code @Version} column is the backbone of the optimistic-locking
 * concurrency strategy described in ARCHITECTURE.md §8 - every update carries the version the
 * client last read, and a mismatch surfaces as a 409 VERSION_CONFLICT rather than a silent
 * overwrite.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(updatable = false, nullable = false, length = 36)
    private UUID id;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
