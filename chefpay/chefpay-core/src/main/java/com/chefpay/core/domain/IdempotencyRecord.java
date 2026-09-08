package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Backs the {@code Idempotency-Key} header pattern (requirement §29/§51): the first request for
 * a given key executes and stores its result here; every retry of that same key (double-click,
 * network retry, WebSocket-reconnect-triggered resubmit) replays the stored result instead of
 * re-executing. Keyed by the raw client-supplied key string plus which operation it was for, so
 * the same key value can't collide across unrelated endpoints.
 */
@Entity
@Table(name = "idempotency_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    private String compositeKey; // "<operation>:<idempotencyKey>"

    @Column(nullable = false)
    private String operation;

    // See AuditLog.oldValue/newValue for why @Column(columnDefinition = "TEXT") is required here
    // alongside @Lob - same PostgreSQL "expecting oid, found text" schema-validation failure
    // otherwise (result_json is TEXT in V2__phase2_orders_schema.sql).
    @Lob
    @Column(columnDefinition = "TEXT")
    private String resultJson;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
