package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;

/** Named AppUser (not User) to avoid colliding with SQL reserved words and Spring Security's own User type. */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class AppUser extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String displayName;

    /** BCrypt hash - never the raw password. */
    @Column(nullable = false)
    private String passwordHash;

    /** BCrypt-hashed numeric PIN for fast terminal login. Null if PIN login is not set up. */
    private String pinHash;

    @ManyToOne(optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Builder.Default
    private boolean active = true;

    /** Round 12: which branches this user may work at (a "chain" restaurant only - see
     * {@code ShellView}'s branch-selection screen, which only appears when this set has 2+
     * entries). Empty means "no branch restriction configured yet" - {@code BranchAccessService}
     * treats an empty set as "every branch" for backward compatibility, since every existing
     * single-branch install has no rows in {@code app_user_branch} at all after the migration and
     * must keep working exactly as it did before this feature existed.
     *
     * <p><b>Fix (production OOM, this round):</b> was {@code FetchType.EAGER}. Each {@link Branch}
     * here eagerly drags in its whole {@link Restaurant} row too (a ~70-column entity including a
     * {@code TEXT} base64 logo image that can be hundreds of KB to a few MB) - EAGER on a
     * many-valued association means Hibernate's single "load this AppUser by id" query (issued on
     * essentially every login/user-lookup) becomes one giant join that duplicates that entire
     * payload once per branch row, then AGAIN for every terminal this user can access (see
     * {@link #terminals}' identical fix below) and every OTHER user who last touched one of those
     * terminals. On a real multi-branch/multi-terminal install this reliably produced a
     * multi-hundred-thousand-row, memory-exploding result set from what should be a handful of
     * cheap lookups - confirmed in production as the direct cause of a
     * {@code java.lang.OutOfMemoryError: Java heap space} / "Ran out of memory retrieving query
     * results" crash. LAZY is safe here without touching any call site: every place that actually
     * calls {@link #getBranches()} (see e.g. {@code AuthController}, {@code UserController},
     * {@code PurchaseOrderController}) does so from a normal HTTP request thread, and this app
     * runs with Spring Boot's default {@code spring.jpa.open-in-view=true}, so the lazy collection
     * still resolves transparently there - just as a small separate query instead of a
     * combinatorial join, exactly when (and only when) something actually needs it. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "app_user_branch",
            joinColumns = @JoinColumn(name = "app_user_id"),
            inverseJoinColumns = @JoinColumn(name = "branch_id"))
    @Builder.Default
    private Set<Branch> branches = new HashSet<>();

    /** The branch a terminal should preselect for this user, skipping the selection screen even
     * when {@link #branches} has 2+ entries. Null = always ask (when there's more than one to pick
     * from). Must be one of {@link #branches} if both are set - enforced in {@code
     * BranchAccessService}, not at the JPA level. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_branch_id")
    private Branch defaultBranch;

    /** Phase 2: a short, unique, staff-visible login identifier (e.g. "CASH001") - the fix for the
     * duplicate-PIN ambiguity bug (see {@code UserAccountService#verifyPinByUserCode}'s javadoc):
     * login now resolves to exactly one account via {@code (userCode, pin)} before the PIN is even
     * checked, rather than scanning every active user's PIN hash and taking the first match. Unlike
     * {@link #username} (used for password/Manager-UI login and typically an email-shaped or
     * internal handle), this is short and easy to read off a badge or type quickly on a POS keypad.
     * Auto-suggested at creation (role-prefix + sequence, e.g. "CASH001", "WAIT002"), editable by
     * an admin, always unique. Null on every pre-Phase-2 row until {@code DataSeeder
     * #ensurePhase2Backfill} assigns one on next boot. */
    @Column(name = "user_code", length = 32)
    private String userCode;

    /** Phase 2: which terminals this user may log into (item 14 of the request) - same
     * empty-means-unrestricted convention {@link #branches} already established (Round 12), now
     * extended one level deeper. A cashier restricted to Terminal 002 only, for instance, still
     * needs their branch (via {@link #branches}) to include that terminal's branch - this is an
     * additional, narrower restriction on top of the branch check, not a replacement for it.
     *
     * <p><b>Fix (production OOM, this round):</b> was {@code FetchType.EAGER} - see {@link
     * #branches}' identical fix javadoc for the full reasoning. This one was actually the worse
     * offender of the two: each {@link Device} here eagerly drags in its own {@link Device#branch}
     * AND {@link Device#lastUser} (see that entity's fix), and {@code lastUser} is a WHOLE OTHER
     * {@code AppUser} - meaning loading this user's terminals could eagerly load every other staff
     * member who ever used one of them, plus THEIR branches/restaurant too. That's the exact
     * fan-out confirmed in the production crash log's SQL (aliases {@code lu1_0}/{@code b3_1}/
     * {@code r4_0} are precisely this "terminal's last user's branch's restaurant" chain). LAZY is
     * safe here for the same reason given on {@link #branches}: every real call site runs inside an
     * HTTP request thread under this app's default {@code open-in-view=true}. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "app_user_terminal",
            joinColumns = @JoinColumn(name = "app_user_id"),
            inverseJoinColumns = @JoinColumn(name = "device_id"))
    @Builder.Default
    private Set<Device> terminals = new HashSet<>();
}
