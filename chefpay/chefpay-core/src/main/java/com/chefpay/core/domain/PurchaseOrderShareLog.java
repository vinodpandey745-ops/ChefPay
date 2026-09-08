package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDateTime;

/**
 * One record of a Purchase Order being shared with its supplier (Round 12 §19-§20) - "sent-by/
 * date/method/recipient/status" exactly as the requirement lists it. A PO can be shared more than
 * once (re-sent, or shared via a second channel) so this is an append-only audit log, not a single
 * "last sent" field on {@link PurchaseOrder} - mirrors {@link com.chefpay.core.service.AuditService}'s
 * own append-only design rather than introducing a second, differently-shaped audit mechanism.
 */
@Entity
@Table(name = "purchase_order_share_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class PurchaseOrderShareLog extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderShareMethod method;

    /** Email address / phone number / printer name / (future) supplier API endpoint - whatever
     * identifies where this went, in a form meaningful for that method. */
    private String recipient;

    @ManyToOne(optional = false)
    @JoinColumn(name = "sent_by", nullable = false)
    private AppUser sentBy;

    @Column(nullable = false)
    private LocalDateTime sentAt;

    /** "SENT" or "FAILED - <reason>" - see {@code PurchaseOrderService#shareWithSupplier}'s javadoc
     * for exactly when each is recorded. A failed send is still logged (never silently dropped) so
     * the sharing history stays a complete, honest record of what was attempted. */
    @Column(nullable = false, length = 500)
    private String status;
}
