package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
 * A registered client instance (a specific JavaFX cashier terminal, a tablet, the kitchen TV...).
 * Registered on first login from a previously-unseen client. Used for session/terminal tracking
 * (requirement §8) and as the {@code deviceId} audit-log field (§23); offline sync bookkeeping
 * (§40) hangs off this in a later phase.
 *
 * <p>Round 17 (Organization/Branch/Terminal identity): this entity IS the "Terminal" of the new
 * identity model rather than a separate parallel entity - a Device already represents exactly
 * "one registered POS/kitchen/tablet client instance", which is what a restaurant means by
 * "terminal". Added {@link #branch} (which shop/branch this terminal is registered at - null on
 * older rows/single-branch installs, matching every other optional-until-configured field in this
 * codebase) and {@link #terminalCode} (a short human-shown identity code, e.g. "T-4F2A", surfaced
 * at login and on the in-app Branches & Terminals screen so staff can tell which physical station
 * they're using) and {@link #active} (soft-disable a retired/lost terminal without deleting its
 * audit/order history, same soft-delete convention as every other entity here).
 */
@Entity
@Table(name = "device")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Device extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceType type;

    /** Fix (production OOM, this round): {@code @ManyToOne} defaults to EAGER when no fetch is
     * given, which is exactly what turned a simple "load this AppUser" query into a runaway join -
     * see {@code AppUser#terminals}' javadoc for the full chain (this field is the "lastUser is a
     * WHOLE OTHER AppUser" link in that chain). LAZY is safe: every real caller ({@code
     * TerminalController}/{@code BranchController} building a terminal DTO's "last used by" label)
     * runs inside an HTTP request thread under this app's default {@code open-in-view=true}. */
    /** Bistrodesk fix (production {@code StackOverflowError}, confirmed via server log on
     * {@code GET /api/users}): {@link AppUser#terminals} is the inverse side of this exact
     * relationship, and Lombok's default {@code @EqualsAndHashCode} includes every declared field
     * on both classes with no cycle protection - so hashing a {@code Device} whose {@link
     * #lastUser} is set walked straight back into that user's {@code terminals} collection,
     * re-hashing this same (or another) {@code Device}, forever. Excluding the back-reference here
     * is the standard fix for a bidirectional JPA association's equals/hashCode (see the identical
     * fix on {@code OrderItem#order}, {@code PurchaseOrderItem#purchaseOrder}, {@code
     * RecipeLine#recipe}, {@code SupplierInvoiceLine#invoice} - all found to have the same latent
     * shape during this investigation, none yet observed to crash in production) - identity for
     * this entity doesn't need to depend on which user last touched it anyway. */
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_user_id")
    private AppUser lastUser;

    private LocalDateTime lastSeenAt;

    /** Which branch/shop this terminal is physically at. Null = not yet assigned (a fresh
     * single-branch install, or a terminal registered before this field existed) - the web app
     * treats a null branch on a single-branch restaurant as "the one branch" implicitly.
     *
     * <p>Fix (production OOM, this round): same EAGER-by-default trap as {@link #lastUser} - and
     * this one also drags in the branch's whole {@link Restaurant} row (see {@code Branch
     * #restaurant}'s fix), so it compounded the same crash from a different angle. LAZY for the
     * same open-in-view reasoning. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Short human-shown terminal identity code (e.g. "T-4F2A"), auto-generated at first
     * registration if the client didn't supply one (see {@code AuthController#registerDevice}).
     * Shown at login and in the Branches & Terminals screen so staff can confirm which physical
     * station they're signed into - distinct from {@link #name}, which is often just a browser/
     * hostname string a user wouldn't recognize at a glance. */
    @Column(name = "terminal_code", length = 32)
    private String terminalCode;

    /** Soft-disable: an inactive terminal is hidden from the Branches & Terminals picker and can no
     * longer register a new login session (see login flow), without losing its historical
     * audit-log / order association. Defaults true so every pre-existing Device row (migrated with
     * no explicit value) stays usable. */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** Phase 2: this terminal's human-facing sequence within its own {@link #branch} ("Terminal
     * 001", "002"...) - assigned once, sequentially, by {@code TerminalController}'s bulk-create
     * ("how many terminals do you want?") or single-create endpoint, unique per branch (see the
     * {@code idx_device_branch_sequence} index). Distinct from {@link #terminalCode} (a short
     * random string used for silent re-registration across restarts) - this is the number staff
     * actually see and select at login. Null for a terminal registered the old way (no branch
     * assigned, or created before Phase 2) - the client falls back to showing {@link #name} for
     * those, same "optional until configured" precedent as every other Phase 1/Round 17 field on
     * this entity. */
    @Column(name = "sequence_no")
    private Integer sequenceNo;
}
