package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "branch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Branch extends BaseEntity {

    /** Fix (production OOM, this round): {@code @ManyToOne} defaults to EAGER when no fetch is
     * given - and {@link Restaurant} is a large (~70-column) entity that includes {@code
     * logoImageBase64}, a {@code TEXT} column that can be hundreds of KB to a few MB. Every branch
     * touched anywhere (a user's assigned branches, a terminal's branch, a plain branch list) was
     * eagerly pulling that whole payload in, and duplicating it per row wherever a branch appeared
     * more than once in a join - confirmed in production as a direct contributor to a
     * {@code java.lang.OutOfMemoryError: Java heap space} crash (see {@code AppUser#branches}' and
     * {@code Device#branch}'s matching fix javadoc for the full chain). LAZY is safe: every real
     * caller ({@code RestaurantController}, DTO-building code) runs inside an HTTP request thread
     * under this app's default {@code spring.jpa.open-in-view=true}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Column(nullable = false)
    private String name;

    private String address;
    private String phone;

    /** Phase 2: a short, system-generated, unique code (e.g. "1100") a POS client's first-run
     * screen validates against before it's allowed to proceed - see {@code BranchController
     * #create}/{@code #byCode} and {@code AppUser#userCode}'s parallel javadoc for why an
     * auto-generated code (not a free-text one an admin could accidentally duplicate) is the
     * chosen design. Null on every pre-Phase-2 row until {@code DataSeeder
     * #ensurePhase2Backfill} assigns one on next boot - same "generate on first read/backfill on
     * boot" convention {@link Restaurant#getOrganizationId()} already established. */
    @Column(name = "branch_code", length = 20)
    private String branchCode;

    /** Soft-disable, same convention as {@link Device#isActive()} - a deactivated branch is hidden
     * from the branch-code validation endpoint and the client's terminal-select screen, without
     * losing its historical order/audit association. Defaults true so every existing row (added
     * with no explicit value) stays usable after the Phase 2 migration. */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** Bistrodesk branch-isolation release (requirement #4, user-confirmed decision: "give each
     * branch its own restaurant profile"). {@link Restaurant} stays the ~70-field INSTALL-wide
     * settings singleton it always was (SMTP, AI keys, retention, payment toggles, etc. - genuinely
     * shared across every branch, untouched by this release) - only the four fields a receipt or a
     * branch's own "restaurant profile" screen actually shows move here, alongside the
     * {@code name}/{@code address}/{@code phone} this entity already had. All four are nullable
     * with NO fallback chain to {@code Restaurant} once seeded: {@code V40} one-time-copies each
     * branch's null fields from the single legacy {@code Restaurant} row at migration time (and
     * {@code DataSeeder} mirrors that for SQLite), so every branch starts with a real value and can
     * then diverge from every other branch independently - see {@code BillingService}'s receipt
     * read path, which now reads these instead of {@code Restaurant}'s. */
    private String gstin;

    private String supportPhone;

    @Column(length = 1000)
    private String receiptFooterText;

    /** Same {@code TEXT}/lazy-fetch caution as {@link Restaurant#getLogoImageBase64()}'s own
     * javadoc - this class is already fetched LAZY everywhere per the fix documented above, so a
     * large base64 payload here doesn't reintroduce that OOM risk. */
    @Column(columnDefinition = "TEXT")
    private String logoImageBase64;

    /** Bistrodesk branch-isolation release (requirement #4 of the follow-up 10-item list): "write a
     * whatsapp meta/twilio/360dialog integration and make it configurable inside setting ... put
     * branch owner phone number as sender." Deliberately per-BRANCH, not a single install-wide
     * {@link Restaurant} field the way {@code aiProvider}/{@code aiApiKey} are: a real WhatsApp
     * Business sender number is independently registered/approved with the provider one number at a
     * time (a Meta phone-number-ID, a Twilio WhatsApp sender, a 360dialog channel each identify ONE
     * specific number), so "the branch owner's phone number as sender" only actually works when each
     * branch owns its own provider registration - exactly this field set, mirroring {@code
     * Restaurant#aiProvider}/{@code #aiApiKey}'s "provider + credential(s), null = not configured"
     * shape. Which provider this branch's WhatsApp Business account is with - "META", "TWILIO", or
     * "DIALOG360" (see {@code com.chefpay.server.purchasing.whatsapp.WhatsAppProvider}). Null/blank
     * until an owner configures one for this branch - {@code PurchaseOrderService#shareWithSupplier}
     * falls back to today's client-side {@code wa.me} deep link whenever this is unset, so nothing
     * breaks for a branch that hasn't bought an API plan yet. */
    private String whatsappProvider;

    /** The branch owner's WhatsApp Business phone number (E.164, e.g. {@code +919876543210}) - the
     * "from" identity every outbound PO share appears to come from once {@link #whatsappProvider}
     * is configured. Must already be the exact number registered/approved with that provider (Meta's
     * phone-number-ID, Twilio's WhatsApp sender, or 360dialog's channel all resolve to one specific
     * number on the provider's side - this field does not itself register a new number with
     * anyone). */
    private String whatsappSenderNumber;

    /** Raw primary credential for {@link #whatsappProvider} - Meta's permanent Graph API access
     * token, Twilio's Auth Token, or 360dialog's API key - stored as plain text, same write-only-
     * credential pattern as {@link Restaurant#getAiApiKey()} (never returned to the client after
     * being set; see {@code BranchDto#whatsappApiKeyConfigured}, a boolean, not this value). */
    private String whatsappApiKey;

    /** Secondary identifier {@link #whatsappProvider} needs alongside {@link #whatsappApiKey} -
     * Meta's Phone Number ID, Twilio's Account SID, or 360dialog's Channel ID/WABA namespace,
     * depending on which provider is selected. Not itself a secret (Twilio's Account SID in
     * particular is routinely visible in dashboards/URLs), but kept alongside the credential above
     * rather than reusing {@link #whatsappSenderNumber} since it's a provider-assigned identifier,
     * not the human-readable phone number. */
    private String whatsappAccountId;

    /** Bistrodesk follow-up requirement ("Local Time Zone During Branch Creation"): an IANA zone id
     * (e.g. {@code "Asia/Kolkata"}, {@code "America/New_York"}) this branch's own date/time
     * functionality should use, distinct from {@link Restaurant#getDefaultTimezone()} which stays
     * the single install-wide fallback. Nullable - {@code V44} backfills every existing branch's
     * null value from the parent {@code Restaurant}'s {@code defaultTimezone} at migration time (and
     * {@code DataSeeder} mirrors that for SQLite) so every branch starts with a real, valid zone; a
     * genuinely new branch created via the Admin console without one explicitly chosen also defaults
     * to the restaurant's zone at creation time (see {@code PlatformOwnerController#createBranch}).
     * {@code ReportService#businessZone()} prefers this field over the restaurant-wide one whenever
     * a specific branch context is resolvable, falling back exactly as before when it isn't. */
    private String timezone;

    /** POS patch (manual KOT print and order completion), configured under Settings > Branches >
     * this branch's own "Billing and Ordering" section - the first plain feature toggle on
     * {@code Branch} (every prior install-wide workflow toggle - {@code kotOptionalEnabled},
     * {@code showDiscountConfirmation}, etc. - lives on the singleton {@link Restaurant} row
     * instead; this is deliberately per-branch so turning it on for one branch can never
     * unintentionally affect another, per the standing branch-isolation rule). When true, the POS
     * screen offers "Print KOT manually without sending to kitchen" - the order never reaches the
     * kitchen/KDS at all and can be checked out and paid immediately, no item status change
     * required (see {@code KotTicketService#buildManualKotText} and {@code OrderController}'s new
     * manual-KOT endpoint). Off by default - every existing branch keeps today's behavior (every
     * order goes through the normal Send to Kitchen / KOT flow) until an owner explicitly opts a
     * specific branch in. */
    @Builder.Default
    @Column(nullable = false)
    private boolean manualKotPrintEnabled = false;
}
