package com.chefpay.core.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A Purchase Order (Round 12 §12-§26) - full CRUD + status lifecycle + role-based approval +
 * offline supplier workflow + receiving. {@code branch} makes every PO's stock impact
 * branch-scoped from creation (§23: "a PO for Branch A only updates Branch A stock") - required
 * (not nullable), same reasoning {@code Floor.branch} already applies, rather than left optional
 * and inferred later from its items.
 *
 * <p>Money is NOT duplicated here the way {@link com.chefpay.core.domain.Order}'s totals are -
 * unlike a bill (which freezes numbers that must survive independent of the items backing them),
 * a PO's total is always just the live sum of {@link PurchaseOrderItem#lineTotal()} across
 * {@link #items}, so there is nothing here to keep in sync and no risk of it drifting from what
 * the items actually say.
 */
@Entity
@Table(name = "purchase_order")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class PurchaseOrder extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String poNumber;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;

    @ManyToOne
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    private LocalDateTime approvedAt;

    @ManyToOne
    @JoinColumn(name = "rejected_by")
    private AppUser rejectedBy;

    private LocalDateTime rejectedAt;

    @Column(length = 1000)
    private String rejectionReason;

    private LocalDateTime submittedAt;

    private LocalDateTime closedAt;

    @Column(length = 1000)
    private String notes;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PurchaseOrderItem> items = new ArrayList<>();
}
