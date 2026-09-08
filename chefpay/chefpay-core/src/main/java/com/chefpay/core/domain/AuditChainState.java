package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Single-row lock/head pointer for {@link AuditLog}'s hash chain (AI Backbone Addendum F1.3).
 * {@code AuditService.record} loads this row under a pessimistic write lock (same pattern as
 * {@code NumberSequenceRepository#findForUpdate}/{@code NumberGeneratorService}) so two concurrent
 * writers can never both read the same "previous hash" and fork the chain. Fixed id ("SINGLETON")
 * rather than a natural key - there is exactly one chain for the whole restaurant today (mirrors
 * {@code AuditLog} itself having no branch/tenant scoping yet).
 */
@Entity
@Table(name = "audit_chain_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuditChainState {

    @Id
    @Column(length = 20, nullable = false)
    private String id;

    /** Hex SHA-256 of the most recently written {@link AuditLog} entry, or a fixed genesis value
     * ("0"-repeated) before the very first entry. */
    @Column(nullable = false, length = 64)
    private String lastHash;

    /** Strictly increasing, gap-free counter mirroring write order - deliberately NOT relying on
     * {@code AuditLog#timestamp} for chain ordering, since two entries can share a millisecond
     * under concurrent writers and wall-clock time is never a safe sort key for a tamper-evident
     * chain. */
    @Column(nullable = false)
    private long lastSeq;
}
