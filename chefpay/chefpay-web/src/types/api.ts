/** Mirrors com.chefpay.server.common.ApiResponse - every ChefPay REST response uses this envelope. */
export interface ApiEnvelope<T> {
  success: boolean
  data: T | null
  errorCode: string | null
  message: string | null
  timestamp: string
  correlationId: string | null
}

export class ApiError extends Error {
  status: number
  errorCode: string | null

  constructor(message: string, status: number, errorCode: string | null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.errorCode = errorCode
  }
}

/** Mirrors com.chefpay.server.auth.LoginResponse. */
export interface BranchSummary {
  id: string
  name: string
}

/** Round 17: mirrors LoginResponse.TerminalSummary - "which terminal you're logging into", shown
 * on the login screen and in the app shell afterward. branchId/branchName are null until this
 * terminal has been assigned to a branch (see the Branches & Terminals settings page). */
export interface TerminalSummary {
  id: string
  terminalCode: string | null
  name: string
  branchId: string | null
  branchName: string | null
}

export interface LoginResponse {
  token: string
  userId: string
  username: string
  displayName: string
  role: string
  permissions: string[]
  branches: BranchSummary[]
  defaultBranchId: string | null
  organizationId: string | null
  organizationName: string | null
  terminal: TerminalSummary
  /** Phase 2: the deterministic login identifier (see AppUser#userCode's javadoc) - null for a
   * user who has never had one assigned (pre-Phase-2 account that has only ever used password
   * login). */
  userCode: string | null
  /** Phase 2: "PASSWORD" or "PIN" - which of the two login shapes actually produced this JWT (see
   * JwtService/AuthenticatedPrincipal). Every PW-gated admin action requires this to be
   * "PASSWORD", regardless of the permission the role otherwise holds. */
  loginMethod: 'PASSWORD' | 'PIN'
}

/** Mirrors com.chefpay.server.auth.LoginRequest. terminalCode/branchId are Round 17 additions -
 * see that record's javadoc for how a returning terminal re-identifies itself across logins.
 * Phase 2 adds userCode (the User Code + PIN login path) and terminalId (the exact Device row
 * id, preferred over the terminalCode/deviceName heuristics once a client knows it - see the
 * backend record's javadoc). */
export interface LoginRequest {
  username?: string | null
  password?: string | null
  pin?: string | null
  userCode?: string | null
  deviceName?: string | null
  deviceType?: string | null
  terminalCode?: string | null
  branchId?: string | null
  terminalId?: string | null
}

/** Mirrors com.chefpay.server.terminals.TerminalDto (Round 17) - a registered POS/kitchen/tablet
 * client instance, doubling as this app's "Terminal" concept (see Device.java's javadoc). */
export interface TerminalDto {
  id: string
  name: string
  terminalCode: string | null
  type: string | null
  branchId: string | null
  branchName: string | null
  active: boolean
  lastUserDisplayName: string | null
  lastSeenAt: string | null
  version: number
  /** Phase 2: this terminal's human-facing per-branch sequence number ("001", "002"...) - null
   * for a terminal registered before Phase 2 or with no branch assigned. */
  sequenceNo: number | null
}

/** Mirrors com.chefpay.server.terminals.UpdateTerminalRequest - null/omitted = unchanged. */
export interface UpdateTerminalRequest {
  name?: string | null
  branchId?: string | null
  active?: boolean | null
  version: number
}

// ---- Phase 2: Branches (com.chefpay.server.branches) ----

/** Mirrors com.chefpay.server.branches.BranchDto - the Manager/Admin "Branches" screen's row
 * shape. terminalCount lets an admin tell at a glance whether a branch is safe to hard-delete.
 * Bistrodesk branch-isolation release (requirement #4): gstin/supportPhone/receiptFooterText/
 * logoImageBase64 are this branch's own restaurant-profile fields. */
export interface BranchDto {
  id: string
  name: string
  branchCode: string
  address: string | null
  phone: string | null
  active: boolean
  terminalCount: number
  version: number
  gstin: string | null
  supportPhone: string | null
  receiptFooterText: string | null
  logoImageBase64: string | null
  /** Bistrodesk follow-up requirement #4 (WhatsApp integration): this branch's WhatsApp Business
   * API config. whatsappApiKeyConfigured masks the raw credential - see UpdateBranchRequest's
   * comment for the write-only whatsappApiKey field. */
  whatsappProvider: string | null
  whatsappSenderNumber: string | null
  whatsappAccountId: string | null
  whatsappApiKeyConfigured: boolean
  /** Follow-up enhancement ("Local Time Zone During Branch Creation"): this branch's own IANA
   * zone id - always populated once V44/DataSeeder's backfill has run. */
  timezone: string | null
  /** POS patch (manual KOT print and order completion) - see the backend Branch entity's
   * manualKotPrintEnabled javadoc. Off by default, per-branch. */
  manualKotPrintEnabled: boolean
}

/** Mirrors com.chefpay.server.branches.BranchByCodeResponse - the PUBLIC (unauthenticated)
 * response for the POS first-run "enter your branch code" screen. */
export interface BranchByCodeResponse {
  branchId: string
  branchName: string
  active: boolean
}

/** Mirrors com.chefpay.server.branches.AccessibleBranchDto - backs GET /api/branches/accessible,
 * the Bistrodesk Phase 5 branch-switcher data source (Dashboard/Reports). Reachable by ANY
 * authenticated user (unlike BranchDto's GET /api/branches, which needs BRANCH_MANAGE/USER_MANAGE/
 * TERMINAL_MANAGE) - see that endpoint's own javadoc. */
export interface AccessibleBranchDto {
  id: string
  name: string
}

/** Mirrors com.chefpay.server.branches.UpdateBranchRequest - null/omitted (other than version)
 * leaves that field unchanged. active:false is the deactivate action (409
 * BRANCH_HAS_ACTIVE_TERMINALS if terminals remain active). Bistrodesk branch-isolation release
 * (requirement #4): gstin/supportPhone/receiptFooterText/logoImageBase64 are this branch's own
 * restaurant-profile fields - an empty string clears a previously-set value, null leaves it
 * unchanged (same convention UpdateRestaurantRequest.logoImageBase64 already uses). */
export interface UpdateBranchRequest {
  name?: string | null
  address?: string | null
  phone?: string | null
  active?: boolean | null
  version: number
  gstin?: string | null
  supportPhone?: string | null
  receiptFooterText?: string | null
  logoImageBase64?: string | null
  /** Bistrodesk follow-up requirement #4 (WhatsApp integration): same null-means-unchanged,
   * empty-string-clears convention as gstin/supportPhone above. whatsappApiKey is write-only -
   * sent here, never returned by BranchDto (see BranchDto#whatsappApiKeyConfigured). */
  whatsappProvider?: string | null
  whatsappSenderNumber?: string | null
  whatsappApiKey?: string | null
  whatsappAccountId?: string | null
  /** Follow-up enhancement ("Local Time Zone During Branch Creation"): an IANA zone id; null/blank
   * leaves the branch's current zone unchanged (this field is never intentionally cleared back to
   * blank the way a logo/footer can be). */
  timezone?: string | null
  /** POS patch (manual KOT print and order completion) - null leaves it unchanged. */
  manualKotPrintEnabled?: boolean | null
}

/** Mirrors com.chefpay.server.branches.BulkCreateTerminalsRequest - item 7's "how many terminals
 * do you want?" prompt. count is 1-50; namePrefix is cosmetic only ("Counter" -> "Counter 001"). */
export interface BulkCreateTerminalsRequest {
  count: number
  namePrefix?: string | null
}

/** Mirrors com.chefpay.server.branches.CreateTerminalRequest - the single "+ Add Terminal"
 * add-on, distinct from the bulk prompt above. Blank name falls back to "Terminal 00N". */
export interface CreateTerminalRequest {
  name?: string | null
}

/** Mirrors com.chefpay.server.dashboard.DashboardDtos. */
export interface DashboardSummary {
  todaySalesTotal: number
  todayOrderCount: number
  todayPaymentCount: number
  openOrderCount: number
  occupiedTableCount: number
  totalTableCount: number
  lowStockItemCount: number
}

export interface DailySalesPoint {
  date: string
  total: number
}

export interface PaymentMethodSales {
  method: string
  total: number
  count: number
}

export interface CategorySales {
  categoryName: string
  total: number
}

export interface TopItem {
  itemName: string
  quantitySold: number
  revenue: number
}

export interface DashboardAnalytics {
  salesTrend: DailySalesPoint[]
  categoryBreakdown: CategorySales[]
  topItems: TopItem[]
  paymentMethods: PaymentMethodSales[]
  branchSales: { branchName: string; total: number }[]
  discountTotalToday: number
  taxTotalToday: number
  averageOrderValueToday: number
  totalInventoryValue: number
}

/** Mirrors com.chefpay.server.menu.MenuDtos. */
export interface MenuItemDto {
  id: string
  categoryId: string
  name: string
  sku: string | null
  plu: string | null
  description: string | null
  price: number
  taxCode: string | null
  stationId: string | null
  stationName: string | null
  vegetarian: boolean
  foodType: string
  available: boolean
  active: boolean
  directSale: boolean
  halfPrice: number | null
  barcode: string | null
  prepTimeMinutes: number | null
  version: number
  // Bistrodesk branch-isolation release (requirement #1) - every item now always belongs to a
  // branch; null here means a legacy, not-yet-backfilled row (see MenuController's javadoc).
  branchId: string | null
  branchName: string | null
}

/** Round 18: parentCategoryId/parentCategoryName are null for a normal top-level category (every
 * pre-Round-18 category) - non-null marks this as a subcategory (e.g. "Veg" under "Main Course"). */
export interface MenuCategoryDto {
  id: string
  name: string
  displayOrder: number
  active: boolean
  version: number
  items: MenuItemDto[]
  parentCategoryId: string | null
  parentCategoryName: string | null
}

/** Mirrors com.chefpay.server.orders.OrderDtos. */
export interface OrderItemDto {
  id: string
  menuItemId: string
  menuItemName: string
  quantity: number
  unitPrice: number
  lineTotal: number
  status: string
  priority: boolean
  specialInstructions: string | null
  modifiersSummary: string | null
  sentAt: string | null
  kotNumber: number | null
  directSale: boolean
  version: number
}

export interface OrderDto {
  id: string
  orderNumber: string
  orderType: string
  tableId: string | null
  tableName: string | null
  customerName: string | null
  customerPhone: string | null
  waiterName: string | null
  cashierName: string | null
  status: string
  paymentStatus: string
  priority: string
  items: OrderItemDto[]
  subtotal: number
  discountAmount: number
  taxAmount: number
  serviceChargeAmount: number
  tipAmount: number
  totalAmount: number
  notes: string | null
  createdAt: string
  updatedAt: string
  version: number
  deliveryBoyId: string | null
  deliveryBoyName: string | null
}

/** Mirrors com.chefpay.server.tables.TableDto. */
export interface TableDto {
  id: string
  floorId: string
  name: string
  seatingCapacity: number
  section: string | null
  status: string
  gridRow: number
  gridColumn: number
  active: boolean
  version: number
}

/** Mirrors com.chefpay.server.tables.TableFloorDto - the "Add Table" floor picker's data source
 * (GET /tables/floors), deliberately not derived from an existing table's floorId any more. */
export interface TableFloorDto {
  id: string
  name: string
}

/** Mirrors com.chefpay.server.kitchen.KitchenDtos.StationDto. */
export interface StationDto {
  id: string
  name: string
  displayOrder: number
  active: boolean
  version: number
}

/** UI Modernization Phase 2 - Move Table request body, mirrors
 * com.chefpay.server.orders.OrderDtos.MoveTableRequest. */
export interface MoveTableRequest {
  tableId: string
  orderVersion: number
}

/** Mirrors com.chefpay.server.billing.BillingDtos. Used by the POS Terminal's Checkout flow. */
export interface TaxLineDto {
  name: string
  ratePercent: number
  taxableAmount: number
  amount: number
}

export interface PaymentDto {
  id: string
  method: string
  amount: number
  tenderedAmount: number | null
  changeAmount: number | null
  referenceNumber: string | null
  receiptNumber: string | null
  receivedByName: string | null
  receivedAt: string
  voided: boolean
  voidReason: string | null
  version: number
}

export interface BillDto {
  orderId: string
  orderNumber: string
  orderStatus: string
  paymentStatus: string
  subtotal: number
  discountAmount: number
  discountReason: string | null
  taxLines: TaxLineDto[]
  taxAmount: number
  serviceChargeAmount: number
  tipAmount: number
  totalAmount: number
  amountPaid: number
  balanceDue: number
  payments: PaymentDto[]
  orderVersion: number
}

export interface RecordPaymentRequest {
  method: string
  amount?: number
  tenderedAmount?: number
  referenceNumber?: string
  tipAmount?: number
  orderVersion: number
}

/** Mirrors com.chefpay.server.theme.ThemeDtos.ThemeSettingsDto - the centralized appearance
 * configuration blob (see AppearancePage / lib/appearance.ts for the shape of the JSON itself). */
export interface ThemeSettingsDto {
  themeJson: string | null
  version: number
}

/** Mirrors com.chefpay.server.support.SupportDtos.SupportSettingsDto - the Help popup's
 * configurable content (support phone/email, Terms & Conditions, Privacy Policy), published from
 * the BistroDesk Admin Panel's Support & Policy Configuration section. Any field null means "not
 * configured yet". */
export interface SupportSettingsDto {
  supportPhone: string | null
  supportEmail: string | null
  termsAndConditions: string | null
  privacyPolicy: string | null
  version: number
}

/** Mirrors com.chefpay.server.customers.CustomerDto - backs the POS Terminal's phone-lookup "Find".
 * Bistrodesk branch-isolation release (requirement #6): branchId/branchName are the customer's
 * one owning branch - null only for a not-yet-backfilled legacy row. */
export interface CustomerDto {
  id: string
  name: string
  phone: string | null
  email: string | null
  notes: string | null
  visitCount: number
  totalSpend: number
  lastVisitAt: string | null
  version: number
  branchId: string | null
  branchName: string | null
}

/** Mirrors com.chefpay.server.customers.CreateCustomerRequest. branchId is optional - a
 * single-branch caller or one with a default branch never needs to pass it; a multi-branch/
 * unrestricted caller picks one explicitly (see CustomersPage's branch picker). */
export interface CreateCustomerRequest {
  name: string
  phone?: string | null
  email?: string | null
  notes?: string | null
  branchId?: string | null
}

/** Mirrors com.chefpay.server.customers.UpdateCustomerRequest - null/omitted = unchanged. */
export interface UpdateCustomerRequest {
  name?: string | null
  phone?: string | null
  email?: string | null
  notes?: string | null
  version: number
}

/** Mirrors com.chefpay.server.billing.BillingDtos.DiscountDto - configured discount presets, used
 * by the POS Terminal's Discount section (real presets, not hardcoded percentages).
 * Bistrodesk Phase 3 (requirement #6): branchId/branchName are null for a preset usable at every
 * branch (every pre-existing preset), set for one restricted to a single branch. */
export interface DiscountDto {
  id: string
  name: string
  type: 'PERCENTAGE' | 'FIXED_AMOUNT'
  value: number
  maxDiscountAmount: number | null
  applicableCategoryId: string | null
  applicableCategoryName: string | null
  active: boolean
  branchId: string | null
  branchName: string | null
  version: number
}

export interface ApplyDiscountRequest {
  discountId?: string | null
  type?: string | null
  value?: number | null
  reason?: string | null
  orderVersion: number
}

/** Round 17 desktop-parity port: GET/POST/PATCH /api/billing/discounts already existed
 * server-side (BillingController) - only the web management UI was missing. */
export interface CreateDiscountRequest {
  name: string
  type: 'PERCENTAGE' | 'FIXED_AMOUNT'
  value: number
  maxDiscountAmount?: number | null
  applicableCategoryId?: string | null
  branchId?: string | null
}

export interface UpdateDiscountRequest {
  name?: string | null
  value?: number | null
  maxDiscountAmount?: number | null
  applicableCategoryId?: string | null
  clearCategory?: boolean
  active?: boolean | null
  branchId?: string | null
  clearBranch?: boolean
  version: number
}

/** Mirrors com.chefpay.server.billing.BillingDtos.TaxDto - GET/POST/PATCH /api/billing/taxes
 * already existed server-side; Round 17 adds the first web management UI for it.
 * Bistrodesk Phase 3 (requirement #6): branchId/branchName are null for the GLOBAL rate for
 * `code`, set for a branch-specific override. */
export interface TaxDto {
  id: string
  name: string
  code: string
  ratePercent: number
  active: boolean
  defaultRate: boolean
  branchId: string | null
  branchName: string | null
  version: number
}

export interface CreateTaxRequest {
  name: string
  code: string
  ratePercent: number
  defaultRate: boolean
  branchId?: string | null
}

/** No branch-reassignment field here, by design - see BillingDtos.UpdateTaxRequest's javadoc. */
export interface UpdateTaxRequest {
  name?: string | null
  ratePercent?: number | null
  active?: boolean | null
  defaultRate?: boolean | null
  version: number
}

/** Mirrors com.chefpay.server.billing.BillingDtos.ReceiptDto - powers the Orders Log's Print action. */
export interface ReceiptDto {
  orderNumber: string
  text: string
}

// ---- Round 15: Menu Editor / Inventory / Reports / Reservations / Users / Settings ----

export interface CreateCategoryRequest {
  name: string
  displayOrder: number
  parentCategoryId?: string | null
}

/** Mirrors com.chefpay.server.menu.MenuDtos.UpdateCategoryRequest - null/omitted = unchanged.
 * parentCategoryId sets/changes the parent (subcategory); clearParentCategory removes it. */
export interface UpdateCategoryRequest {
  name?: string | null
  displayOrder?: number | null
  active?: boolean | null
  parentCategoryId?: string | null
  clearParentCategory?: boolean
  version: number
}

/** Round 18: POST /api/menu/categories/{id}/merge - moves every item from that category into
 * targetCategoryId, then removes the now-empty source category. */
export interface MergeCategoryRequest {
  targetCategoryId: string
}

export interface CreateMenuItemRequest {
  categoryId: string
  name: string
  sku?: string | null
  plu?: string | null
  description?: string | null
  price: number
  taxCode?: string | null
  stationId?: string | null
  vegetarian: boolean
  foodType?: string | null
  directSale: boolean
  halfPrice?: number | null
  barcode?: string | null
  prepTimeMinutes?: number | null
  // Bistrodesk branch-isolation release (requirement #1) - optional here only because the server
  // resolves this caller's own default/sole branch when omitted; a multi-branch/unrestricted
  // caller with no default MUST supply one or the request is rejected (BRANCH_REQUIRED) - it no
  // longer falls back to a shared/centralized item.
  branchId?: string | null
}

/** Mirrors com.chefpay.server.menu.MenuDtos.UpdateItemRequest - null/omitted = unchanged, except
 * clearStation (explicit unset) as the backend documents. */
export interface UpdateMenuItemRequest {
  name?: string | null
  price?: number | null
  taxCode?: string | null
  stationId?: string | null
  clearStation?: boolean
  vegetarian?: boolean | null
  foodType?: string | null
  available?: boolean | null
  active?: boolean | null
  directSale?: boolean | null
  halfPrice?: number | null
  barcode?: string | null
  prepTimeMinutes?: number | null
  // Bistrodesk branch-isolation release (requirement #1) - reassigns this item to a different
  // branch; omitted leaves it unchanged. There is deliberately no "clearBranch" flag anymore (Phase
  // 3 originally allowed moving an item back to a shared/centralized null branch) - every item
  // keeps a real branch for its whole life once created.
  branchId?: string | null
  version: number
}

/** Mirrors com.chefpay.server.menu.MenuDtos.UpdateDescriptionRequest - PATCH /menu/items/{id}/description. */
export interface UpdateDescriptionRequest {
  description: string | null
  version: number
}

/** Mirrors com.chefpay.server.ai.AiMenuImportDtos - the real AI Bulk Import feature, previously
 * unused by any client. `analyze` returns a reviewable draft; nothing is saved until `apply`. */
export interface AiMenuImportDraftItem {
  name: string
  categoryName: string
  price: number
  foodType: string | null
  description: string | null
}

export interface AiMenuImportAnalyzeResponse {
  items: AiMenuImportDraftItem[]
}

// ---- Inventory (com.chefpay.server.inventory) ----

export interface InventoryItemDto {
  id: string
  name: string
  unit: string
  quantityOnHand: number
  reorderThreshold: number | null
  costPerUnit: number | null
  lowStock: boolean
  active: boolean
  version: number
  preferredSupplierId: string | null
  preferredSupplierName: string | null
  // Bistrodesk branch-isolation release (requirement #3): every item now always belongs to a
  // branch - null here means a legacy, not-yet-backfilled row (see InventoryController's javadoc),
  // which should not occur once the server-side branch backfill has run.
  branchId: string | null
  branchName: string | null
}

export interface CreateInventoryItemRequest {
  name: string
  unit: string
  openingQuantity: number
  reorderThreshold?: number | null
  costPerUnit?: number | null
  // Bistrodesk branch-isolation release (requirement #3): optional here only because the server
  // resolves this caller's own default/sole branch when omitted (see
  // BranchAccessService#resolveEffectiveBranchId) - a multi-branch/unrestricted caller with no
  // default MUST supply one or the request is rejected (BRANCH_REQUIRED), it no longer falls back
  // to a shared/unassigned item.
  branchId?: string | null
}

export interface UpdateInventoryItemRequest {
  name?: string | null
  unit?: string | null
  reorderThreshold?: number | null
  costPerUnit?: number | null
  active?: boolean | null
  version: number
}

export interface RecordInventoryTransactionRequest {
  type: 'RECEIVE' | 'ADJUST' | 'DEDUCT' | 'WASTE'
  quantity: number
  reason: string
  itemVersion: number
}

export interface InventoryTransactionDto {
  id: string
  itemId: string
  type: string
  quantity: number
  resultingQuantity: number
  reason: string | null
  recordedByName: string | null
  createdAt: string
}

/** Bistrodesk branch-isolation release (requirement #6): branchId/branchName are the supplier's
 * one owning branch - null only for a not-yet-backfilled legacy row. */
export interface SupplierDto {
  id: string
  name: string
  contactPerson: string | null
  phone: string | null
  email: string | null
  address: string | null
  notes: string | null
  active: boolean
  version: number
  branchId: string | null
  branchName: string | null
}

/** Mirrors com.chefpay.server.purchasing.SupplierDtos.CreateSupplierRequest. branchId is optional -
 * same convention as CreateCustomerRequest.branchId. */
export interface CreateSupplierRequest {
  name: string
  contactPerson?: string | null
  phone?: string | null
  email?: string | null
  address?: string | null
  notes?: string | null
  branchId?: string | null
}

/** Mirrors com.chefpay.server.purchasing.SupplierDtos.UpdateSupplierRequest - null/omitted = unchanged. */
export interface UpdateSupplierRequest {
  name?: string | null
  contactPerson?: string | null
  phone?: string | null
  email?: string | null
  address?: string | null
  notes?: string | null
  active?: boolean | null
  version: number
}

/** Mirrors com.chefpay.server.inventory.InventoryDtos.SetPreferredSupplierRequest - supplierId: null clears it. */
export interface SetPreferredSupplierRequest {
  supplierId: string | null
  version: number
}

// ---- Reports (com.chefpay.server.reports.ReportDtos) - GET /api/reports/sales?from=&to= ----

export interface PaymentMethodTotalDto {
  method: string
  total: number
  paymentCount: number
}

export interface TopItemDto {
  menuItemName: string
  quantitySold: number
  revenue: number
}

export interface CategoryTotalDto {
  categoryName: string
  quantitySold: number
  revenue: number
}

export interface OrderTypeTotalDto {
  orderType: string
  orderCount: number
  revenue: number
}

export interface EmployeeTotalDto {
  employeeName: string
  totalCollected: number
  paymentCount: number
}

export interface TipTotalDto {
  waiterName: string
  totalTips: number
  orderCount: number
}

/** Mirrors com.chefpay.server.reports.ReportDtos.TodayDto - backs GET /reports/today, the
 * branch-aware "what date is it" ReportsPage's quick-range buttons anchor on instead of the
 * browser's own local clock (see that endpoint's own javadoc). */
export interface ReportTodayDto {
  today: string
}

/** Mirrors com.chefpay.server.reports.ReportDtos.SalesReportDto. */
export interface SalesReportDto {
  from: string
  to: string
  totalSales: number
  orderCount: number
  paymentCount: number
  averageOrderValue: number
  paymentMethodBreakdown: PaymentMethodTotalDto[]
  topItems: TopItemDto[]
  categoryBreakdown: CategoryTotalDto[]
  orderTypeBreakdown: OrderTypeTotalDto[]
  employeeBreakdown: EmployeeTotalDto[]
  tipBreakdown: TipTotalDto[]
  totalTips: number
}

/** Mirrors com.chefpay.server.reports.ReportDtos.BranchTotalDto - branchId is null for the
 * "Unassigned (non-table orders)" bucket, see that record's javadoc. */
export interface BranchTotalDto {
  branchId: string | null
  branchName: string
  totalSales: number
  orderCount: number
}

/** Mirrors com.chefpay.server.reports.ReportDtos.ConsolidatedBranchReportDto - backs GET
 * /api/reports/branches (Bistrodesk Phase 4/5: requires ADVANCED_REPORTS + REPORT_VIEW). */
export interface ConsolidatedBranchReportDto {
  from: string
  to: string
  totalSales: number
  branches: BranchTotalDto[]
}

// ---- Reservations (com.chefpay.server.reservations) ----

export type ReservationStatus = 'PENDING' | 'CONFIRMED' | 'SEATED' | 'CANCELLED' | 'NO_SHOW'

export interface ReservationDto {
  id: string
  customerName: string
  customerPhone: string | null
  partySize: number
  reservedFor: string
  // Bistrodesk Phase 8 - the resolved effective hold length/end (this reservation's own override,
  // else the restaurant's configured default) - see ReservationDto's own javadoc server-side.
  durationMinutes: number
  // The raw override underneath durationMinutes - null means this booking just uses today's
  // restaurant default, not that it's explicitly pinned to it. Use this (never durationMinutes)
  // to prefill an edit form's duration field, or every edit silently bakes in an override.
  durationOverrideMinutes: number | null
  reservedUntil: string
  tableId: string | null
  tableName: string | null
  notes: string | null
  status: ReservationStatus
  version: number
  // Bistrodesk Phase 2 - which branch this booking is for (resolved server-side; see
  // ReservationController's javadoc for the "table wins, else fall back" rule).
  branchId: string | null
}

export interface CreateReservationRequest {
  customerName: string
  customerPhone?: string | null
  partySize: number
  reservedFor: string
  tableId?: string | null
  // Bistrodesk Phase 2 - required when tableId is omitted on a multi-branch install (ignored/
  // cross-checked against the table's own branch otherwise); a single-branch install needs it
  // for neither case (falls back to "the one branch").
  branchId?: string | null
  // Bistrodesk Phase 8 - only consulted when tableId is omitted: runs the capacity check against
  // this Area's tables (rejecting with other areas that have room, if it doesn't fit).
  areaName?: string | null
  // Bistrodesk Phase 8 - overrides this booking's hold length; omitted/null uses the restaurant's
  // configured default.
  durationMinutes?: number | null
  notes?: string | null
}

export interface UpdateReservationRequest {
  customerName?: string | null
  customerPhone?: string | null
  partySize?: number | null
  reservedFor?: string | null
  tableId?: string | null
  clearTable?: boolean
  branchId?: string | null
  durationMinutes?: number | null
  notes?: string | null
  status?: ReservationStatus | null
  version: number
}

/** Mirrors com.chefpay.server.reservations.AreaAvailabilityDto (Bistrodesk Phase 8). */
export interface AreaAvailabilityDto {
  areaName: string
  totalCapacity: number
  freeCapacity: number
  sufficient: boolean
}

/** Mirrors com.chefpay.server.areas.AreaDtos.AreaDto. */
export interface AreaDto {
  id: string
  branchId: string
  name: string
  displayOrder: number
  active: boolean
  version: number
}

// ---- Users / Roles / Permissions (com.chefpay.server.users) ----

export interface UserDto {
  id: string
  username: string
  displayName: string
  role: string
  active: boolean
  hasPin: boolean
  branchIds: string[]
  branchNames: string[]
  defaultBranchId: string | null
  createdAt: string | null
  version: number
  /** Phase 2: the deterministic login identifier (see AppUser#userCode's javadoc). */
  userCode: string | null
  /** Phase 2: per-terminal access restriction, empty = unrestricted - same convention as
   * branchIds/branchNames above. */
  terminalIds: string[]
  terminalNames: string[]
}

/** Mirrors com.chefpay.server.users.CreateUserRequest - userCode is optional, blank/omitted
 * auto-generates one from the role name (see GET /users/suggested-credentials). */
export interface CreateUserRequest {
  username: string
  displayName: string
  password: string
  pin?: string | null
  role: string
  userCode?: string | null
}

/** Mirrors com.chefpay.server.users.SuggestedCredentialsResponse - a suggestion only, nothing is
 * persisted by requesting one. */
export interface SuggestedCredentialsResponse {
  userCode: string
  pin: string
}

/** Mirrors com.chefpay.server.users.UpdateUserBranchesRequest - branchIds empty/null =
 * unrestricted (same convention as UserDto.branchIds). */
export interface UpdateUserBranchesRequest {
  branchIds: string[]
  defaultBranchId: string | null
  version: number
}

/** Mirrors com.chefpay.server.users.UpdateUserTerminalsRequest - terminalIds empty/null =
 * unrestricted (same convention one level deeper than branches above). */
export interface UpdateUserTerminalsRequest {
  terminalIds: string[]
  version: number
}

/** Mirrors com.chefpay.server.users.ChangePinRequest - item 9's "Change PIN" as its own action,
 * distinct from the general update endpoint. No version field - a PIN change never conflicts
 * with another field being edited concurrently. */
export interface ChangePinRequest {
  newPin: string
}

/** Mirrors com.chefpay.server.users.ChangeUserCodeRequest. */
export interface ChangeUserCodeRequest {
  newUserCode: string
  version: number
}

export interface UpdateUserRequest {
  displayName?: string | null
  role?: string | null
  active?: boolean | null
  newPassword?: string | null
  newPin?: string | null
  version: number
}

// Bistrodesk follow-up requirement #7 ("move the role and permission to admin portal... remove
// completely from application"): RoleDto/UpdateRolePermissionsRequest/PermissionDto used to live
// here for the POS app's now-removed Roles & Permissions tab (UsersPage.tsx) - that screen's
// backend (GET/PATCH /api/roles, GET /api/permissions) is removed too, replaced by an equivalent
// platform-owner-key-gated screen in the Admin console (see PlatformOwnerController's own javadoc).
// The POS app still needs a role NAME for its "Add Staff"/"Edit Staff" role picker - that stays a
// plain hardcoded string list (UsersPage.tsx's ROLE_NAMES), never these DTOs.

// ---- Restaurant / Settings (com.chefpay.server.restaurant) ----

/** Round 17: expanded to the FULL com.chefpay.server.restaurant.RestaurantDto (previously only a
 * subset was modeled here - SMTP/AI/EOD/security/data-retention fields existed on the backend
 * record but chefpay-web had no type for them, which is exactly why the Settings page couldn't
 * surface a "Backup & Restore"/AI/Email tab before). Field order mirrors the backend record. */
export interface RestaurantDto {
  id: string
  name: string
  organizationId: string | null
  organizationName: string | null
  /** Phase 2: Organization profile fields (item 4). status is READ-ONLY here - it's set only by
   * the platform owner (a separate, non-restaurant-facing API), never editable from this side -
   * see UpdateRestaurantRequest below, which deliberately has no status field. */
  contactEmail: string | null
  addressLine1: string | null
  addressLine2: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED'
  currencySymbol: string
  defaultTimezone: string
  gstin: string | null
  supportPhone: string | null
  serviceChargePercent: number
  requireKitchenSyncForServed: boolean
  receiptFooterText: string | null
  onlineOrderZomatoEnabled: boolean
  onlineOrderSwiggyEnabled: boolean
  enabledPaymentMethods: string
  upiVpaId: string | null
  upiPayeeName: string | null
  cardPaymentEnabled: boolean
  cardTerminalNote: string | null
  cashDrawerEnabled: boolean
  receiptPrinterName: string | null
  receiptPaperWidthChars: number
  autoPrintOnlineOrders: boolean
  autoPrintReceiptOnPayment: boolean
  deliveryBoyFeatureEnabled: boolean
  kotOptionalEnabled: boolean
  logoImageBase64: string | null
  smtpHost: string | null
  smtpPort: number | null
  smtpUsername: string | null
  smtpPasswordConfigured: boolean
  smtpFromAddress: string | null
  smtpUseTls: boolean
  aiFeaturesEnabled: boolean
  aiProvider: string | null
  aiModel: string | null
  aiApiKeyConfigured: boolean
  aiMenuImportEnabled: boolean
  aiInsightsChatEnabled: boolean
  aiReorderDraftsEnabled: boolean
  aiAnomalyFlaggingEnabled: boolean
  aiMenuDescriptionsEnabled: boolean
  aiNightlySummaryEnabled: boolean
  dashboardViewMode: 'STANDARD' | 'GRAPHICAL' | 'BOTH'
  kitchenServiceMode: 'DETAILED' | 'SIMPLE'
  showDiscountConfirmation: boolean
  poApprovalRequired: boolean
  aiReplenishmentNotesEnabled: boolean
  ocrUseAiVisionAssist: boolean
  biometricOverrideEnabled: boolean
  autoPurgeEnabled: boolean
  dataRetentionDays: number
  defaultOpeningFloat: number
  cashVarianceThreshold: number
  eodZReportRecipientEmails: string | null
  marginErosionThresholdPercent: number
  autoPoFromSuggestionsEnabled: boolean
  criticalAlertEscalationMinutes: number
  criticalAlertRecipientEmails: string | null
  nlAssistantWriteCommandsEnabled: boolean
  // Bistrodesk Phase 3 - purely informational Menu Editor default; see MenuItemDto.branchId for
  // what actually determines an item's visibility.
  menuCentralized: boolean
  // POS patch - master on/off for per-item kitchen status updates (KDS + POS live sync).
  itemLevelKitchenStatusEnabled: boolean
  version: number
  branches: {
    id: string
    name: string
    address: string | null
    phone: string | null
    version: number
    /** POS patch (manual KOT print and order completion) - see Branch#manualKotPrintEnabled's javadoc. */
    manualKotPrintEnabled: boolean
  }[]
}

/** Round 17: expanded to mirror every field UpdateRestaurantRequest accepts server-side (null/
 * omitted = unchanged, the backend's universal convention for this record). */
export interface UpdateRestaurantRequest {
  name?: string | null
  organizationId?: string | null
  organizationName?: string | null
  /** Phase 2: Organization profile fields (item 4) - null/omitted = unchanged, same convention as
   * every other field on this record. There is deliberately no `status` field here - status is
   * platform-owner-only, see RestaurantDto's own comment. */
  contactEmail?: string | null
  addressLine1?: string | null
  addressLine2?: string | null
  city?: string | null
  state?: string | null
  postalCode?: string | null
  country?: string | null
  currencySymbol?: string | null
  defaultTimezone?: string | null
  gstin?: string | null
  supportPhone?: string | null
  serviceChargePercent?: number | null
  requireKitchenSyncForServed?: boolean | null
  receiptFooterText?: string | null
  onlineOrderZomatoEnabled?: boolean | null
  onlineOrderSwiggyEnabled?: boolean | null
  enabledPaymentMethods?: string | null
  upiVpaId?: string | null
  upiPayeeName?: string | null
  cardPaymentEnabled?: boolean | null
  cardTerminalNote?: string | null
  cashDrawerEnabled?: boolean | null
  receiptPrinterName?: string | null
  receiptPaperWidthChars?: number | null
  autoPrintOnlineOrders?: boolean | null
  autoPrintReceiptOnPayment?: boolean | null
  deliveryBoyFeatureEnabled?: boolean | null
  kotOptionalEnabled?: boolean | null
  logoImageBase64?: string | null
  smtpHost?: string | null
  smtpPort?: number | null
  smtpUsername?: string | null
  smtpPassword?: string | null
  smtpFromAddress?: string | null
  smtpUseTls?: boolean | null
  aiFeaturesEnabled?: boolean | null
  aiProvider?: string | null
  aiApiKey?: string | null
  aiModel?: string | null
  aiMenuImportEnabled?: boolean | null
  aiInsightsChatEnabled?: boolean | null
  aiReorderDraftsEnabled?: boolean | null
  aiAnomalyFlaggingEnabled?: boolean | null
  aiMenuDescriptionsEnabled?: boolean | null
  aiNightlySummaryEnabled?: boolean | null
  dashboardViewMode?: string | null
  kitchenServiceMode?: string | null
  showDiscountConfirmation?: boolean | null
  poApprovalRequired?: boolean | null
  aiReplenishmentNotesEnabled?: boolean | null
  ocrUseAiVisionAssist?: boolean | null
  biometricOverrideEnabled?: boolean | null
  autoPurgeEnabled?: boolean | null
  dataRetentionDays?: number | null
  defaultOpeningFloat?: number | null
  cashVarianceThreshold?: number | null
  eodZReportRecipientEmails?: string | null
  marginErosionThresholdPercent?: number | null
  autoPoFromSuggestionsEnabled?: boolean | null
  criticalAlertEscalationMinutes?: number | null
  criticalAlertRecipientEmails?: string | null
  nlAssistantWriteCommandsEnabled?: boolean | null
  menuCentralized?: boolean | null
  itemLevelKitchenStatusEnabled?: boolean | null
  version: number
}

// ---- Phase 2: Subscription / Licensing (com.chefpay.server.subscription) - read-only for this
// UI; every write happens through the separate, platform-owner-only /platform/** API, which this
// app never calls. ----

/** Mirrors com.chefpay.server.subscription.SubscriptionDtos.SubscriptionDto. remainingDays can be
 * negative (past expiry, inside the grace period). warningThresholdsDays is a CSV string like
 * "30,15,7,3,1" - days-before-expiry to show a warning banner. */
export interface SubscriptionDto {
  id: string
  branchId: string | null
  branchName: string | null
  planName: string
  planId: string
  status: 'ACTIVE' | 'EXPIRING_SOON' | 'GRACE_PERIOD' | 'EXPIRED' | 'SUSPENDED'
  startDate: string
  expiryDate: string
  remainingDays: number
  gracePeriodDays: number
  warningThresholdsDays: string
  supportPhone: string | null
  version: number
}

/** Mirrors com.chefpay.server.subscription.SubscriptionDtos.PlanDto - the read-only "available
 * plans" list; featureCodes is the set of feature codes this plan unlocks (see EntitlementsResponse
 * below for which of them are actually enabled on THIS install right now). */
export interface PlanDto {
  id: string
  name: string
  description: string | null
  durationDays: number
  price: number
  gstPercent: number
  maxBranches: number | null
  maxTerminals: number | null
  maxUsers: number | null
  trial: boolean
  active: boolean
  displayOrder: number
  featureCodes: string[]
}

/** Mirrors com.chefpay.server.subscription.SubscriptionDtos.EntitlementsResponse - the full list
 * of feature codes currently unlocked for this install, computed live from the subscription's
 * real status every time (never cached server-side beyond a short TTL, never baked into the JWT -
 * see EntitlementService's javadoc). Backs the feature-gating hook (hooks/useEntitlements.ts). */
export interface EntitlementsResponse {
  enabledFeatures: string[]
}

// ---- Subscription Renewal and Plan Upgrade requirement (com.chefpay.server.subscription) -
// Razorpay-driven renew/plan-change flow. Every write here is gated server-side on a genuine
// verified Razorpay payment signature - see SubscriptionRenewalService's javadoc. ----

/** Mirrors com.chefpay.server.subscription.SubscriptionPaymentDtos.InitiateRenewalRequest. */
export interface InitiateRenewalRequest {
  branchId?: string | null
  purpose: 'REACTIVATE' | 'PLAN_CHANGE'
  targetPlanId?: string | null
}

/** Mirrors com.chefpay.server.subscription.SubscriptionPaymentDtos.InitiateRenewalResponse -
 * everything the Razorpay Checkout widget needs to open the payment sheet. razorpayKeyId is
 * Razorpay's PUBLIC key, safe to use directly in the browser. */
export interface InitiateRenewalResponse {
  subscriptionPaymentId: string
  razorpayOrderId: string
  razorpayKeyId: string
  amountPaise: number
  currency: string
  planName: string
  description: string
}

/** Mirrors com.chefpay.server.subscription.SubscriptionPaymentDtos.VerifyRenewalRequest - the
 * three fields Razorpay Checkout's own `handler` success callback hands back. */
export interface VerifyRenewalRequest {
  subscriptionPaymentId: string
  razorpayOrderId: string
  razorpayPaymentId: string
  razorpaySignature: string
}

export interface CancelRenewalRequest {
  subscriptionPaymentId: string
}

/** Mirrors com.chefpay.server.subscription.SubscriptionPaymentDtos.SubscriptionPaymentDto - one
 * row of the "Recent Payments" list. paymentMethod is frequently null (best-effort only). */
export interface SubscriptionPaymentDto {
  id: string
  planName: string
  purpose: 'REACTIVATE' | 'PLAN_CHANGE'
  amount: number
  currency: string
  status: 'CREATED' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
  paymentMethod: 'CASH' | 'CARD' | 'UPI' | 'WALLET' | 'OTHER' | null
  previousExpiryDate: string | null
  newExpiryDate: string | null
  failureReason: string | null
  createdAt: string
}

// ---- Purchase Orders (com.chefpay.server.purchasing) - Bistrodesk Phase 9 ----

export type PurchaseOrderStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'SENT_TO_SUPPLIER'
  | 'PARTIALLY_RECEIVED'
  | 'RECEIVED'
  | 'CLOSED'
  | 'CANCELLED'

/** Mirrors com.chefpay.server.purchasing.PurchaseOrderDtos.PurchaseOrderItemDto. Ordered/received/
 * accepted/damaged/rejected/remaining quantities are all running totals across every receiving
 * event so far, not one delivery's worth - see PurchaseOrderService#receiveItems's javadoc. */
export interface PurchaseOrderItemDto {
  id: string
  inventoryItemId: string
  inventoryItemName: string
  unit: string
  orderedQuantity: number
  unitPrice: number
  lineTotal: number
  receivedQuantity: number
  acceptedQuantity: number
  damagedQuantity: number
  rejectedQuantity: number
  remainingQuantity: number
  receivingNotes: string | null
  version: number
}

/** Mirrors com.chefpay.server.purchasing.PurchaseOrderDtos.PurchaseOrderDto. */
export interface PurchaseOrderDto {
  id: string
  poNumber: string
  branchId: string
  branchName: string
  supplierId: string
  supplierName: string
  status: PurchaseOrderStatus
  createdByName: string | null
  approvedByName: string | null
  approvedAt: string | null
  rejectedByName: string | null
  rejectedAt: string | null
  rejectionReason: string | null
  submittedAt: string | null
  closedAt: string | null
  notes: string | null
  totalAmount: number
  items: PurchaseOrderItemDto[]
  createdAt: string
  version: number
  /** Bistrodesk follow-up requirement #4 (WhatsApp integration): true when this PO's branch has a
   * real WhatsApp Business API configured (Branch#whatsappProvider) - the client uses this to
   * decide whether sharing via WhatsApp sends straight through the API or falls back to opening
   * the wa.me deep link. See ShareModal#send in PurchaseOrdersPage.tsx. */
  whatsappApiConfigured: boolean
}

export interface CreatePurchaseOrderItemRequest {
  inventoryItemId: string
  orderedQuantity: number
  unitPrice: number
}

export interface CreatePurchaseOrderRequest {
  branchId: string
  supplierId: string
  notes?: string | null
  items: CreatePurchaseOrderItemRequest[]
}

/** Full item-list replace - see UpdatePurchaseOrderRequest's server-side javadoc for why. */
export interface UpdatePurchaseOrderRequest {
  supplierId?: string | null
  notes?: string | null
  items: CreatePurchaseOrderItemRequest[]
  version: number
}

export interface RejectPurchaseOrderRequest {
  reason?: string | null
  version: number
}

export interface ShareRequest {
  method: 'PRINT' | 'EMAIL' | 'WHATSAPP' | 'API'
  recipient?: string | null
  version: number
}

export interface ReceiveLineRequest {
  purchaseOrderItemId: string
  receivedQuantity: number
  acceptedQuantity: number
  damagedQuantity?: number | null
  rejectedQuantity?: number | null
  notes?: string | null
}

export interface ReceiveItemsRequest {
  lines: ReceiveLineRequest[]
  version: number
}

export interface ShareLogDto {
  id: string
  method: 'PRINT' | 'EMAIL' | 'WHATSAPP' | 'API'
  recipient: string | null
  sentByName: string | null
  sentAt: string
  status: string
}

export interface ReplenishmentSuggestionDto {
  inventoryItemId: string
  itemName: string
  unit: string
  quantityOnHand: number
  reorderThreshold: number | null
  pendingOrderedQuantity: number
  suggestedQuantity: number
  reason: string
}

export interface ReplenishmentSuggestionsResponse {
  suggestions: ReplenishmentSuggestionDto[]
  aiNarrative: string | null
}

export interface CreateDraftPoFromSuggestionsRequest {
  branchId: string
  supplierId: string
  notes?: string | null
  items: CreatePurchaseOrderItemRequest[]
}
