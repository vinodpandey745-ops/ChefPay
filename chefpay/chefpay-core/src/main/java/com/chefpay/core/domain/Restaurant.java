package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/** Root of the multi-branch tree. A single row is created on first boot for v1 deployments. */
@Entity
@Table(name = "restaurant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Restaurant extends BaseEntity {

    @Column(nullable = false)
    private String name;

    /** Round 17: identity fields for the new Organization/Branch/Terminal model - added as plain
     * fields on the existing single-row Restaurant/Branch tree (per the chosen "identity fields on
     * today's model" scope) rather than a full multi-tenant rewrite with real data isolation. An
     * {@code organizationId} exists so a future multi-branch-restaurant-group deployment can group
     * several {@code Restaurant} rows under one organization without a schema change today; for a
     * typical single-restaurant install this is just a stable label shown at login alongside the
     * branch/terminal so staff can confirm "which deployment" they're in. Null/blank on first boot -
     * {@code RestaurantSetupService}/Settings auto-fills a generated {@code ORG-XXXXXXXX} value the
     * first time this is read if still blank, so every install ends up with a real value without
     * forcing a blocking setup step. */
    @Column(name = "organization_id", length = 64)
    private String organizationId;

    /** Human-readable organization/company name shown at login next to {@link #organizationId} -
     * defaults to {@link #name} (the restaurant's own name) when blank, since a single-restaurant
     * install has no separate "parent company" name to give. */
    @Column(name = "organization_name")
    private String organizationName;

    // ---- Phase 2: Organization profile fields (contact/address/status) ----
    // gstin/supportPhone already existed before Phase 2 - the Tax/GST and contact-phone parts of
    // the Organization requirement were already satisfied, only these were actually missing.

    private String contactEmail;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String country;

    /** Phase 2: the org's own operational status, set by the platform owner (see
     * {@code PlatformOwnerController}) - NOT self-service from the restaurant's own Manager UI,
     * since a restaurant deactivating its own account would be an odd, support-bypassing action.
     * See {@link RestaurantStatus}'s javadoc for how this differs from subscription status. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RestaurantStatus status = RestaurantStatus.ACTIVE;

    @Builder.Default
    @Column(nullable = false)
    private String currencySymbol = "₹";

    @Builder.Default
    @Column(nullable = false)
    private String defaultTimezone = "Asia/Kolkata";

    private String gstin;
    private String supportPhone;

    /** Flat percent applied to (subtotal - discount) when a bill is generated. Zero = no service charge. Phase 4. */
    @Builder.Default
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal serviceChargePercent = BigDecimal.ZERO;

    /**
     * When true, {@code OrderTakingView}'s "Mark Order Served" action only becomes available once
     * the kitchen has actually progressed every item on the order (i.e. real item-level
     * acceptance/prep/serve via the KDS, not just "sent"). When false (the pre-Settings-screen
     * behavior this codebase shipped with), a waiter can mark the whole order served the moment
     * there's nothing left un-sent, regardless of what the kitchen has actually done with it -
     * fine for a small operation where the same person runs both ends, not fine once kitchen and
     * floor are different people who need to stay honestly in sync. Defaults to true (synced) -
     * added specifically because that gap was reported as confusing in practice.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean requireKitchenSyncForServed = true;

    /** Custom closing line for the plain-text receipt ({@code BillingService
     * #generateReceiptText}), e.g. "Thank you, visit again!" or a return policy. Null/blank falls
     * back to the receipt formatter's own default line rather than printing nothing - see that
     * method's javadoc. Deliberately just this one text field: a real "rounding rule" (round the
     * amount actually collected to the nearest rupee) touches payment/balance-due math that's
     * covered by BillingServiceTest's split-payment assertions, and a "logo on print" option
     * doesn't apply to this app's plain-text/ESC-POS-style receipts (no image support) - both are
     * deliberately left out of this round rather than shipped half-right. */
    private String receiptFooterText;

    /**
     * Plain on/off record for whether this restaurant currently accepts new orders from each
     * aggregator - mirrors a real commercial POS's "Store on/off Status" toggle. This does NOT
     * wire up a live connection to either platform: {@code chefpay-plugin-api}'s {@code
     * OnlineOrderProvider} extension point exists for exactly that but has never been implemented
     * against Zomato's or Swiggy's real API (would need API credentials, a webhook receiver for
     * incoming orders, and order-status callbacks - a substantial integration project of its own).
     * Flipping these toggles today only changes what Settings displays; nothing reads them yet to
     * gate an actual order feed. Kept as two plain booleans rather than a generic map/table since
     * exactly these two platforms is what was asked for and a third can be added the same way.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean onlineOrderZomatoEnabled = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean onlineOrderSwiggyEnabled = false;

    // ---- Payment methods / UPI / card / cash drawer / printer config (Round 6) ----

    /** Comma-separated {@link PaymentMethod} names this restaurant currently accepts at the
     * register, e.g. {@code "CASH,CARD,UPI"} - drives which options {@code BillingView}'s Record
     * Payment dialog shows. Deliberately a plain CSV rather than a join table: five fixed enum
     * values, no per-method metadata beyond on/off, a join table would be pure ceremony. The server
     * does NOT enforce this list on {@code recordPayment} - same "clients hide what's disabled, the
     * server stays the only trust boundary" rule as every other permission-gated action (§7) - a
     * manager can still record an unusual payment method from a direct API call if they need to. */
    @Builder.Default
    @Column(nullable = false, length = 100)
    private String enabledPaymentMethods = "CASH,CARD,UPI";

    /** UPI VPA (Virtual Payment Address, e.g. {@code restaurant@okhdfcbank}) this restaurant
     * receives UPI payments at. Null/blank disables the "Show QR" action on UPI payments - see
     * {@code UpiQrGenerator}'s javadoc for exactly what the generated QR does and, importantly,
     * does NOT do (no payment-gateway confirmation callback - a UPI QR is just a standard
     * `upi://pay?...` deep link encoding the payee/amount; the customer's UPI app handles the
     * actual transfer, and staff still confirms/marks the payment received manually, the same as
     * today's CARD/WALLET/OTHER methods with an external terminal). Real-time payment confirmation
     * would need a PSP (payment service provider) integration - a genuinely different, much larger
     * project (webhook receiver, reconciliation, PCI-adjacent concerns) than generating a QR code. */
    private String upiVpaId;

    /** Payee display name shown inside the UPI QR/deep link (the "pn" parameter) - falls back to
     * {@link #name} when blank, matching how most UPI apps show a merchant name to the payer. */
    private String upiPayeeName;

    /** Whether this restaurant accepts card payments today - purely a UI toggle for
     * {@code BillingView}'s payment method list (via {@link #enabledPaymentMethods} really, this
     * flag is kept separate so Settings can show/hide the terminal-note field below without
     * conflating "accepts cards" with "cards happens to be in the enabled-methods CSV"). No live
     * card-network/payment-gateway integration exists or is implied - a card payment today is
     * recorded the same way it always has been (an external card terminal handles the actual
     * charge; this app just records that a CARD payment of a given amount was received). */
    @Builder.Default
    @Column(nullable = false)
    private boolean cardPaymentEnabled = false;

    /** Free-text note about the card terminal in use (e.g. "Pine Labs terminal at Counter 1",
     * "Razorpay POS device") - purely informational for staff, not read by any code path. */
    private String cardTerminalNote;

    /** Whether a cash drawer is wired up to open on cash sales - see {@code ReceiptPrinter
     * #openCashDrawer}'s javadoc for exactly how (and how reliably) that works: sending a raw
     * ESC/POS "kick drawer" pulse to {@link #receiptPrinterName} via the OS print spooler. This
     * only works with an ESC/POS-compatible thermal receipt printer with the drawer wired through
     * its kick-out port (the standard setup) - it will silently do nothing useful against a generic
     * inkjet/laser printer, which is why this is an explicit opt-in toggle rather than always-on. */
    @Builder.Default
    @Column(nullable = false)
    private boolean cashDrawerEnabled = false;

    /** OS print-queue name (as returned by {@code javax.print.PrintServiceLookup}) used for silent/
     * unattended printing - the cash-drawer kick pulse above, and auto-printed online-order KOTs
     * (see {@link #autoPrintOnlineOrders}). The existing manual "Print Bill"/"Print Receipt" actions
     * (`ReceiptPrinter#show`) are unaffected - they keep showing the OS print dialog so staff can
     * pick any printer, this field is specifically for the two actions that need to happen without
     * a dialog popping up. Null/blank disables both silent-print features (they fall back to no-op
     * with a clear message rather than guessing a printer). */
    private String receiptPrinterName;

    /** Printable width in characters for the divider/heading lines of both the customer receipt
     * ({@code BillingService#generateReceiptText}) and the kitchen ticket
     * ({@code ReceiptPrinter#buildKotText}) - 32 suits common 58mm thermal paper, 40 (the default,
     * matching this codebase's original hardcoded value) or 48 suits 80mm. Deliberately does NOT
     * reflow the itemized money columns inside {@code BillingService#line} - those stay a fixed
     * width regardless, since that's the one part of the receipt with real money formatting behind
     * it and this project's discipline is to leave money-adjacent formatting alone unless there's a
     * real bug (see {@link #receiptFooterText}'s javadoc for the same call on a rounding rule). */
    @Builder.Default
    @Column(nullable = false)
    private int receiptPaperWidthChars = 40;

    /** When true, a brand-new order with {@code orderType == ONLINE_ORDER} auto-prints a kitchen
     * ticket via {@link #receiptPrinterName} the moment its {@code ORDER_CREATED} event arrives
     * (see {@code ShellView}'s websocket subscription), instead of waiting for staff to notice it
     * on the new Online Orders screen and print it by hand. Falls back to doing nothing (not an
     * error popup) if {@link #receiptPrinterName} isn't configured or doesn't match a real OS
     * printer - see {@code ReceiptPrinter#printSilently}'s javadoc. */
    @Builder.Default
    @Column(nullable = false)
    private boolean autoPrintOnlineOrders = false;

    /** Round 11: when true, the moment a bill is paid in full (balance due reaches zero),
     * {@code BillingView} prints the customer receipt straight to {@link #receiptPrinterName} with
     * no dialog and no separate "View Receipt" click first - see {@code ReceiptPrinter
     * #printSilently}'s javadoc for exactly how (and how reliably) that works, and the same
     * fallback rule every silent-print feature here follows: if this is off, {@link
     * #receiptPrinterName} isn't configured, or the print itself fails, the screen falls back to
     * today's manual "View Receipt" button rather than ever silently dropping the receipt. */
    @Builder.Default
    @Column(nullable = false)
    private boolean autoPrintReceiptOnPayment = false;

    /**
     * When true, gates on the "Delivery Boy" roster feature (Round 9) - the Delivery Boys admin
     * screen's assignment control appears on {@code OnlineOrdersView} rows, letting staff assign a
     * rider from {@link DeliveryBoy}'s roster to a delivery/online order. Defaults to false, same
     * "off unless a restaurant actually needs it" default this codebase uses for {@code
     * onlineOrderZomatoEnabled}/{@code onlineOrderSwiggyEnabled} above - a dine-in-only restaurant
     * has no use for a delivery roster cluttering its order screens.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean deliveryBoyFeatureEnabled = false;

    /** Round 11: "sent to kitchen option should be configurable... when physically verified then
     * can directly bill (without kitchen interference)". When true, {@code OrderTakingView} shows a
     * "Bill Directly (Skip Kitchen)" action alongside the normal "Send to Kitchen" one - it walks
     * the order straight from PLACED to SERVED without ever assigning a KOT number or notifying the
     * Kitchen Display, for the case where a cashier has already physically verified the order with
     * kitchen staff themselves. Off by default: existing behavior (every order must go through
     * "Send to Kitchen") is unchanged unless a restaurant explicitly opts in - see {@code
     * OrderService#updateOrderStatus}'s server-side gate, which is what actually enforces this (a
     * client can't bypass it just by not showing the button). */
    @Builder.Default
    @Column(nullable = false)
    private boolean kotOptionalEnabled = false;

    /** POS patch (kitchen item status updates): master on/off for letting kitchen staff update an
     * individual ordered item's own status from the Kitchen Display, instead of only the existing
     * whole-ticket "advance all" action. Off by default - existing kitchen workflow (KDS's
     * ticket-level advance, item status still tracked and shown, just not individually editable by
     * kitchen staff) is unchanged unless a restaurant explicitly opts in. When on, item-level
     * status changes still sync live to POS/KDS the same way every other order/kitchen event
     * already does (see {@code useLiveTopics} on both screens) - this flag does not gate that live
     * channel itself, only whether the KDS UI exposes per-item controls. */
    @Builder.Default
    @Column(nullable = false)
    private boolean itemLevelKitchenStatusEnabled = false;

    // ---- Receipt delivery via email (Round 11) ----

    /** SMTP host used to send an emailed receipt (e.g. {@code smtp.gmail.com}) - null/blank means
     * email receipt-sending isn't configured, same "presence of this field is the on/off switch,
     * no separate boolean" convention {@link #receiptPrinterName} already uses. Every restaurant
     * brings its own SMTP account (a free Gmail/Outlook account with an app password works fine
     * for typical single-location volume); this app has no shared outbound mail relay of its own. */
    private String smtpHost;

    /** SMTP port - 587 (STARTTLS) is the overwhelmingly common default, so that's what a fresh
     * install shows in Settings rather than leaving this blank. */
    @Builder.Default
    private Integer smtpPort = 587;

    private String smtpUsername;

    /** Raw SMTP password/app-password, stored as plain text - same write-only-credential pattern
     * as {@link #aiApiKey}: NEVER returned to the client after being set (see {@code
     * RestaurantDto#smtpPasswordConfigured}, a boolean, not this value). */
    private String smtpPassword;

    /** The "From" address on a sent receipt email - many SMTP providers reject a send whose From
     * doesn't match the authenticated account, so this is typically the same address as {@link
     * #smtpUsername}, but kept separate in case a provider allows a distinct display address. */
    private String smtpFromAddress;

    /** STARTTLS on port 587 (true) vs. plain/implicit-SSL on port 465 (false) - covers the two
     * setups every mainstream consumer SMTP provider (Gmail, Outlook, Yahoo) actually offers. */
    @Builder.Default
    private boolean smtpUseTls = true;

    // ---- Branding (Round 7) ----

    /** Base64-encoded logo image (whatever format the file was uploaded as - PNG/JPG), shown at
     * the top of the Dashboard screen and configurable from Settings. Stored as plain Base64 text
     * rather than a binary/BLOB column or a separate file-storage service: this app has no existing
     * file-upload infrastructure (every other config value is plain JSON over the same
     * {@code PUT /api/restaurant} the rest of Settings already uses), and a single small logo image
     * easily fits as text without needing one. {@code TEXT} column type (not the default
     * VARCHAR(255)) so a realistic image doesn't get silently truncated on Postgres/MySQL - see the
     * V9 migration. Null/blank = no logo configured, screens fall back to showing nothing rather
     * than a broken image. Client-side upload deliberately caps the source file size (see
     * {@code SettingsView}) so this never grows large enough to bloat every restaurant-config
     * fetch across the app. */
    @Column(columnDefinition = "TEXT")
    private String logoImageBase64;

    // ---- AI Features (Round 10) ----

    /** Which AI provider {@code aiApiKey} belongs to - "OPENAI", "ANTHROPIC", or "GEMINI" (see
     * {@code com.chefpay.server.ai.AiProvider}). Null/blank until an admin picks one in Settings.
     * Kept as a plain string here (not the enum type - {@code chefpay-core} has no dependency on
     * {@code chefpay-server}) and parsed/validated on the server side where it's actually used. */
    private String aiProvider;

    /** Raw API key for {@link #aiProvider}, stored as plain text same as every other credential-
     * shaped config value in this entity (see {@code receiptPrinterName}'s neighbors) - there is no
     * secrets-manager/encryption-at-rest layer in this codebase. NEVER returned to the client after
     * being set (see {@code RestaurantDto#aiApiKeyConfigured} - a boolean, not this value) - the
     * Settings screen can only write a new key, never read the current one back, the same
     * write-only-credential pattern most apps use for API keys. Null/blank = AI features cannot
     * actually run regardless of {@link #aiFeaturesEnabled}'s value - see {@code AiService}. */
    private String aiApiKey;

    /** Optional model-name override (e.g. "gpt-4o-mini", "claude-3-5-sonnet-20241022",
     * "gemini-1.5-flash"). Null/blank = {@code AiService} uses a sensible per-provider default -
     * most small-restaurant owners configuring this from Settings won't know or care what a
     * "model" is, so this stays optional rather than required. */
    private String aiModel;

    /** Master AI switch. Deliberately NOT sufficient on its own for any AI feature to actually run -
     * {@code AiService} additionally requires {@link #aiApiKey} to be set (a blank key means "not
     * configured yet", so flipping this on before entering a key is harmless, matching the request
     * that the option only meaningfully turns on once the API is actually configured) and requires
     * that specific feature's own flag below to also be true. Three ANDed gates (master, key
     * present, per-feature) rather than one - a restaurant can have AI configured but only trust it
     * for, say, menu descriptions and not the audit-anomaly flagging, without losing the whole
     * feature set to enable one part of it. */
    @Builder.Default
    @Column(nullable = false)
    private boolean aiFeaturesEnabled = false;

    /** AI Menu Setup from Photo/PDF - extract categories/items/prices/food-type from a photo of an
     * existing paper menu into Menu Management for review before saving (nothing is auto-saved -
     * see {@code MenuController}'s new AI-import endpoint). */
    @Builder.Default
    @Column(nullable = false)
    private boolean aiMenuImportEnabled = false;

    /** "Ask Your Data" - a plain-language question answered from this restaurant's own recent
     * sales/report data (via {@code ReportService}), shown on the Dashboard. */
    @Builder.Default
    @Column(nullable = false)
    private boolean aiInsightsChatEnabled = false;

    /** Smart reorder drafts - when an inventory item crosses its reorder threshold, draft a
     * ready-to-send supplier order message sized to recent consumption velocity, offered on the
     * Alerts screen next to that low-stock notification. */
    @Builder.Default
    @Column(nullable = false)
    private boolean aiReorderDraftsEnabled = false;

    /** Audit anomaly flagging - scans recent Audit Log activity (voids, discounts, complimentary
     * marks) and proactively surfaces unusual patterns on the Audit Log screen, instead of leaving
     * an owner to notice them by reading the raw log. */
    @Builder.Default
    @Column(nullable = false)
    private boolean aiAnomalyFlaggingEnabled = false;

    /** AI-written menu item descriptions - generates a one-line description for a menu item from
     * its name/category/food-type, offered in Menu Management's item dialogs. */
    @Builder.Default
    @Column(nullable = false)
    private boolean aiMenuDescriptionsEnabled = false;

    /** Nightly AI summary - a once-daily plain-language recap (top sellers, anything unusual, low
     * stock) delivered as a Notification to the Alerts inbox, generated by a scheduled job. */
    @Builder.Default
    @Column(nullable = false)
    private boolean aiNightlySummaryEnabled = false;

    // ---- Round 12: Dashboard / Kitchen / Billing workflow configuration ----

    /** "STANDARD" (today's plain KPI-card dashboard), "GRAPHICAL" (charts), or "BOTH" - drives which
     * section(s) {@code DashboardView} renders. Kept as a plain string (not an enum column) for the
     * same cross-module reason {@link #aiProvider} is a string - chefpay-core has no dependency on
     * a chefpay-javafx-only enum, and this is validated server-side against the three known values. */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String dashboardViewMode = "STANDARD";

    /** "DETAILED" (today's per-item Accept/Start/Ready/Served flow) or "SIMPLE" (a single "Serve
     * All" action per order, see {@code KitchenService#serveAllItems}). Off (DETAILED) by default -
     * existing kitchens keep exactly today's behavior unless they opt in. */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String kitchenServiceMode = "DETAILED";

    /** Whether {@code BillingView}'s "Generate Bill" action shows the "discount can't be changed
     * afterward" warning before proceeding. Defaults to true (today's always-shown behavior)
     * unchanged unless a restaurant explicitly turns it off. */
    @Builder.Default
    @Column(nullable = false)
    private boolean showDiscountConfirmation = true;

    // ---- Round 12: Purchase Order approval ----

    /** Master on/off for the PO approval gate. When true (default), a user without
     * {@code PURCHASE_ORDER_APPROVE} who creates a PO lands in PENDING_APPROVAL rather than
     * APPROVED - see {@code PurchaseOrderService#createPurchaseOrder}. When false, every PO is
     * approved immediately on creation regardless of who created it (still audit-logged as
     * "auto-approved - approval disabled"). Per-role granularity comes for free through the
     * existing Role Management screen's permission assignment rather than a second parallel
     * "which roles need approval" config - a role either holds PURCHASE_ORDER_APPROVE or it
     * doesn't, exactly like every other permission-gated action in this codebase. */
    @Builder.Default
    @Column(nullable = false)
    private boolean poApprovalRequired = true;

    /** When true, an inventory replenishment suggestion also gets a plain-language AI-written note
     * (via the existing bring-your-own-key AI provider config) alongside its deterministic numbers.
     * Gated the same three-way way every AI feature here is: {@link #aiFeaturesEnabled}, a non-blank
     * {@link #aiApiKey}, and this flag. Falls back to numbers-only with no note (never an error)
     * if any of those three isn't satisfied - see {@code InventoryReplenishmentService}. */
    @Builder.Default
    @Column(nullable = false)
    private boolean aiReplenishmentNotesEnabled = false;

    // ---- Round 13: AI Backbone Addendum - Automated EOD & Loss Prevention (Phase 1) ----

    /** Default starting cash-drawer float a new {@code EodSession} is seeded with (F1.2's expected-
     * cash formula: opening float + cash sales + payouts - cash refunds). Snapshotted onto the
     * session at start time (see {@code EodSession#openingFloat}'s javadoc) rather than read live,
     * so editing this later never rewrites an in-progress or historical day's math. */
    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal defaultOpeningFloat = BigDecimal.ZERO;

    /** F1.2's variance band: {@code abs(physicalTotal - expectedTotal) <= this} classifies as
     * ACCEPTABLE_VARIANCE rather than HIGH_VARIANCE_FLAGGED. Requirement's own default is "±₹100 /
     * ±$10" - kept as a plain configurable amount (not currency-aware scaling) since a restaurant
     * already picks a sensible number for its own currency/volume when it sets this in Settings. */
    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cashVarianceThreshold = new BigDecimal("100.00");

    /** Comma-separated email addresses the Z-Report (F1.7) is sent to on every EOD finalize. Blank/
     * empty = finalize still succeeds, it just skips the email step (same "optional integration,
     * never blocking" rule {@link #smtpHost}'s absence already follows for receipt emails).
     *
     * <p><b>Explicit {@code name} is required here</b> (Round 18 hardening): Hibernate's default
     * naming strategy collapses the consecutive-capitals run in {@code eodZReportRecipientEmails}
     * (Z immediately followed by R) into one word segment, deriving the physical column name
     * {@code eodzreport_recipient_emails} - but {@code V19__round13_ai_backbone_eod_fraud_audit.sql}
     * (written by hand, before this mismatch was known) created the column as
     * {@code eod_z_report_recipient_emails}. That divergence is invisible on the SQLite dev profile
     * (Hibernate's {@code ddl-auto=update} just creates whatever name it derives, consistently with
     * itself) but fails schema validation the moment a real Postgres/MySQL deployment runs with
     * {@code ddl-auto=validate} against the Flyway-migrated schema - which is exactly what happened
     * on first production deploy. Pinning the name here to match the already-applied migration
     * (rather than editing that migration, which would break Flyway's checksum of an already-run
     * script) fixes it with no database change at all. */
    @Column(name = "eod_z_report_recipient_emails", length = 1000)
    private String eodZReportRecipientEmails;

    // ---- Round 14: AI Backbone Addendum Phases 2-4 (Inventory Intelligence, Predictive/Adaptive,
    //      Conversational AI & Omnichannel Alerting) ----

    /** F3.2's margin-erosion trigger: when a {@link MenuItem}'s margin (based on its {@link Recipe}
     * cost) falls at or below this percent, {@code PriceSuggestionService} raises a {@link
     * PriceChangeSuggestion}. Requirement's own example threshold is 15% - kept configurable since
     * "acceptable margin" varies enormously by cuisine/market. */
    @Builder.Default
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal marginErosionThresholdPercent = new BigDecimal("15.00");

    /** F3.1's opt-in gate for {@code AutoReplenishmentScheduler}: when false (the default), the
     * seasonality-aware forecast still runs and is visible on the Replenishment screen, but no
     * {@code PurchaseOrder} is ever created automatically - a manager always uses the existing
     * "create draft PO from suggestions" action instead. See {@code ReplenishmentService
     * #runAutoPoGeneration}'s javadoc for the further per-item conditions (a {@code
     * InventoryItem#preferredSupplier} and an unambiguous single branch) this flag alone does not
     * relax. */
    @Builder.Default
    @Column(nullable = false)
    private boolean autoPoFromSuggestionsEnabled = false;

    /** F4.2: minutes an UNREVIEWED High/Critical {@link Anomaly} may sit before {@code
     * AlertEscalationScheduler} re-notifies every configured channel a second time, flagged as an
     * escalation. Requirement's own example is "if unresolved after 30 minutes, escalate." */
    @Builder.Default
    @Column(nullable = false)
    private int criticalAlertEscalationMinutes = 30;

    /** F4.2's Email channel recipient list for critical fraud/loss-prevention anomaly alerts -
     * same comma-separated convention as {@link #eodZReportRecipientEmails}, and deliberately a
     * separate list from it (a Z-Report recipient and a "call me immediately about theft" recipient
     * are very often different people). Blank/empty = the Email channel is simply skipped
     * (recorded as {@code NotificationLogStatus#SKIPPED_NOT_CONFIGURED}), same as every other
     * optional integration in this codebase. */
    @Column(length = 1000)
    private String criticalAlertRecipientEmails;

    /** F4.1's gate for the NL Ops Assistant's bounded WRITE command set (86-ing an item, etc.) -
     * deliberately separate from {@link #aiInsightsChatEnabled} (today's read-only "Ask Your Data"):
     * a restaurant can keep the analytics chat on while leaving write-commands off, or vice versa.
     * Still requires the same three AI gates every other AI feature requires ({@link
     * #aiFeaturesEnabled}, a configured key/provider) - see {@code AiOpsAssistantService}. */
    @Builder.Default
    @Column(nullable = false)
    private boolean nlAssistantWriteCommandsEnabled = false;

    // ---- Final round: F2.3 (OCR invoice intake), F4.5 (biometric) ----

    /** F2.3's opt-in for the "cloud OCR API can be offered as a higher-accuracy opt-in" half of
     * the requirement: when true (and the standard three AI gates - {@link #aiFeaturesEnabled}, a
     * configured key/provider - are also satisfied), {@code SupplierInvoiceService} tries the
     * existing multi-provider AI vision pipeline ({@code AiService#chatWithImage}, already proven
     * out by the Round 10 AI Menu Import feature) BEFORE falling back to on-device Tesseract OCR;
     * when false (the default, matching Tess4J as the SRS's primary/offline-friendly
     * recommendation), Tesseract is tried first and AI vision is still used as a fallback if
     * Tesseract itself is unavailable/fails and AI happens to be configured - real extracted data
     * beats none, regardless of this flag's preference ordering. Either way, nothing is ever
     * auto-applied without manager review (F2.3's own requirement) - this flag only changes which
     * extraction attempt runs first. */
    @Builder.Default
    @Column(nullable = false)
    private boolean ocrUseAiVisionAssist = false;

    /** F4.5's master switch: whether this restaurant has a fingerprint reader installed and wants
     * Level-3 overrides offered a biometric alternative to PIN entry (see {@code
     * BiometricAuthProvider} in chefpay-plugin-api). PIN remains the mandatory fallback on every
     * terminal regardless of this flag - see F4.5's own "PIN remains mandatory... biometric is
     * additive, not a replacement." Off by default since most installs have no such hardware. */
    @Builder.Default
    @Column(nullable = false)
    private boolean biometricOverrideEnabled = false;

    /** Master switch for the scheduled data-retention/auto-purge job ({@code
     * com.chefpay.server.retention.DataRetentionService}), added specifically to keep a
     * long-running SQLite-backed small-restaurant install's database file bounded in size over
     * time. <b>Off by default</b> - deliberately opt-in, since enabling it permanently deletes old
     * notification logs, old resolved (non-escalated) anomalies, stale OCR invoice photos, AND old
     * fully-paid orders/payments past {@link #dataRetentionDays}. It never touches the audit log
     * (immutable per F1.3, no exceptions) or anything not yet reconciled through EOD Finalize -
     * see that service's own javadoc for the complete, deliberately conservative scope. */
    @Builder.Default
    @Column(nullable = false)
    private boolean autoPurgeEnabled = false;

    /** How many days of history to keep before {@link #autoPurgeEnabled}'s job deletes something -
     * default 30 ("at least one month"). Only consulted at all when {@link #autoPurgeEnabled} is
     * true. */
    @Builder.Default
    @Column(nullable = false)
    private int dataRetentionDays = 30;

    /** Bistrodesk Phase 3: a purely informational Shop-level default for the Menu Editor UI - true
     * (default, today's only behavior) nudges a newly-created item towards the shared/centralized
     * menu (no branch picker shown by default); false nudges towards branch-specific by default
     * (a chain that mostly runs distinct per-branch menus). This flag does NOT itself gate
     * visibility or get read by any backend enforcement - {@link MenuItem#getBranch()} being null
     * or set is the only thing that actually determines whether an item is shared or branch-
     * specific, so flipping this flag alone changes no existing item's visibility. */
    @Builder.Default
    @Column(nullable = false)
    private boolean menuCentralized = true;

    /** Bistrodesk Phase 8 (requirement #25): {@link Reservation} only ever stores a start instant
     * ({@code reservedFor}) - table-blocking/overlap detection needs an end instant too, so this is
     * the restaurant-wide default slot length used whenever a reservation doesn't set its own
     * {@code Reservation#durationMinutes} override. 90 minutes is a common full-service turnaround
     * default; see {@code ReservationService#resolveDurationMinutes} for the override/fallback
     * chain (per-reservation override, then this, then a last-resort in-code constant for the
     * pathological case of a reservation with no resolvable branch at all). */
    @Builder.Default
    @Column(nullable = false)
    private int defaultReservationDurationMinutes = 90;
}
