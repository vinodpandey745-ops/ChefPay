# ChefPay — Architecture & Design Document

Status: **Phase 4 (Billing) code-complete, pending compiler/CI verification.** Phases 1-3
(Foundation, Orders, Kitchen Display) are code-complete and reviewed; Phases 1-2 have additionally
been built and run on the user's own machine. This document is produced before Phase 1 code per
the project's development instructions, and is updated as later phases land.

---

## 1. Architecture Diagram

```
                                ┌───────────────────────────┐
                                │      chefpay-server        │
                                │   Spring Boot 3 / Java 21  │
                                │                            │
                                │  REST API   WebSocket(STOMP)│
                                │  Spring Security (JWT)     │
                                │  Spring Data JPA           │
                                └──────────────┬─────────────┘
                                               │
                        ┌──────────────────────┼──────────────────────┐
                        │                      │                      │
                 SQLite (dev)          PostgreSQL/MySQL (prod)   Flyway migrations
                        │                      │
                        └──────────────────────┴──────────────────────┘
                                               │
                 ┌─────────────────────────────┼─────────────────────────────┐
                 │                              │                              │
         REST + STOMP/WS                 REST + STOMP/WS                REST + STOMP/WS
                 │                              │                              │
         ┌───────▼────────┐            ┌────────▼────────┐           ┌────────▼────────┐
         │  chefpay-javafx │            │  chefpay-web     │           │  chefpay-web     │
         │  Cashier POS    │            │  Waiter tablet    │           │  Kitchen KDS /   │
         │  (desktop)      │            │  (responsive PWA) │           │  Manager (PWA)   │
         └─────────────────┘            └───────────────────┘           └───────────────────┘
```

All business logic lives in `chefpay-server`. Every client (JavaFX, tablet/mobile web, kitchen
display, manager dashboard) is a thin presentation layer over the same REST + WebSocket API — no
client re-implements pricing, tax, discount, or state-machine rules.

`chefpay-web` (tablet/waiter, kitchen, manager) is **not** part of Phase 1. Phase 1 ships the
server foundation and the JavaFX shell only; the web client is scoped into Phase 2/3 once orders
and kitchen tickets exist to display.

## 2. Module Structure (Maven multi-module)

```
chefpay/
├── pom.xml                       parent POM (dependency management)
├── chefpay-plugin-api/           extension-point interfaces (payment providers, online-order
│                                  platforms, notification channels...) — no dependency on core,
│                                  so third-party integrations can be built against it alone.
├── chefpay-core/                 domain entities, repositories, framework-agnostic business
│                                  services. No web/REST/WebSocket code lives here.
├── chefpay-server/                Spring Boot application: REST controllers, WebSocket/STOMP
│                                  config, Spring Security, DB migrations, main class.
│                                  Internally packaged by business module (auth, users,
│                                  restaurant, tables, menu, ... orders/billing/kitchen/etc.
│                                  added in later phases).
├── chefpay-javafx/                Desktop POS client. Talks to chefpay-server only over REST +
│                                  WebSocket — it does not touch the database or chefpay-core
│                                  directly. MVVM-ish split: screens (View) / ViewModel /
│                                  ApiClient / WebSocketClient.
└── docs/                          this document and future ADRs.
```

`chefpay-web` (tablet/kitchen/manager PWA) and `chefpay-plugins/*` (concrete payment/online-order
plugin implementations) are added as their own modules starting Phase 3/4 — the module list above
is what exists after Phase 1.

## 3. Database ER Diagram (Phase 1 + Phase 2 entities)

```
Restaurant 1───* Branch 1───* Floor 1───* RestaurantTable 1───* Order
                                              │                    │
Role *───* Permission                        │  (Section stored   ├───* OrderItem ──* MenuItem
   │                                          │   as a nullable    │
   *                                          │   free-text        ├── waiter: AppUser
AppUser ───────────────────────────────────────────────────────────┴── cashier: AppUser
   │
   *
Device (registered client instance)

MenuCategory 1───* MenuItem *───0..1 KitchenStation
                        │
                        *  (via taxCode, not a real FK - see Tax)
                     Tax (named rate; MenuItem.taxCode matches Tax.code, or falls back to the
                          restaurant's one defaultRate=true row)

Order 1───* Payment ──received by: AppUser
Discount (standalone catalog of presets - not FK'd from Order; applying one just copies its
          type/value onto Order.discountAmount/discountReason)
CashMovement ── recorded by: AppUser (standalone log, not FK'd from Order/Payment)

AuditLog (references any entity generically via entityType + entityId)

IdempotencyRecord (standalone; composite_key → cached result, no FK)
NumberSequence     (standalone; series_key → current_value, backs order-number generation)
```

Full ER model (all phases) is tracked incrementally in this file as each phase lands. Entities
planned for later phases (Modifier, Customer, Reservation, InventoryItem, Shift, SyncTransaction,
Notification...) are listed in §9 Database Core Entities so the eventual shape is visible now, but
are **not** created until their phase. `Tax`, `Discount`, `Payment`, `CashMovement` landed in Phase
4 (this drop) rather than waiting - `KitchenTicket` was folded into the existing `OrderItem`
status/timestamp columns instead of becoming its own entity (see §13's Phase 3 note).

## 4. Core Entity Relationship Model — Phase 1-4, 5a

| Entity | Key fields | Notes |
|---|---|---|
| `Restaurant` | id (UUID), name, currencySymbol, defaultTimezone, serviceChargePercent | Root of the multi-branch tree; single row for v1 deployments. `serviceChargePercent` (Phase 4, default zero) is applied to the post-discount subtotal when a bill is generated. |
| `Branch` | id (UUID), restaurant, name, address, phone | Multiple branches supported in the model from day one, even though v1 operates one. |
| `Floor` | id (UUID), branch, name, displayOrder | |
| `RestaurantTable` | id (UUID), floor, name, seatingCapacity, section, status, gridRow/gridColumn, version | `status` enum: `AVAILABLE, RESERVED, OCCUPIED, ORDER_PLACED, PREPARING, READY, BILL_REQUESTED, PAYMENT_PENDING, CLOSED, BLOCKED` (full set from spec, even though only a subset is reachable until Orders exist). Colors are **not** on the entity — see §7 theming note. |
| `Role` | id (UUID), name (ADMIN/MANAGER/CASHIER/WAITER/KITCHEN/VIEW_ONLY seed data), permissions | Permissions configurable, not hardcoded in code beyond seed defaults. |
| `Permission` | id (UUID), code, description | e.g. `ORDER_CREATE`, `DISCOUNT_APPROVE`, `REPORT_VIEW`. |
| `AppUser` | id (UUID), username, displayName, passwordHash, pin, role, active, version | PIN is hashed too, not stored plain. |
| `Device` | id (UUID), name, type (JAVAFX_POS/WEB/KDS), lastSeenAt | Registered on first login from a new client; used later for session/terminal tracking. |
| `MenuCategory` | id (UUID), name, displayOrder | |
| `MenuItem` | id (UUID), category, name, sku, price, taxCode, station, vegFlag, active, version | Modifier groups attach starting Phase 5. `station` (nullable, Phase 3) routes this item's lines to a `KitchenStation`; null means "show on every KDS screen" — fine for a single-station kitchen. |
| `KitchenStation` | id (UUID), name (unique), displayOrder, active, version | Phase 3. A physical/logical prep screen (Grill, Cold, Beverages...). Optional by design — a kitchen with one screen never has to create any. |
| `AuditLog` | id (UUID), userId, deviceId, entityType, entityId, action, oldValue, newValue, reason, timestamp | Generic append-only log, per §23. |
| `Order` | id (UUID), orderNumber, orderType, table, customerName/Phone, waiter, cashier, status, paymentStatus, priority, subtotal/discountAmount/taxAmount/serviceChargeAmount/tipAmount/totalAmount (all `BigDecimal`), discountReason, notes, sentToKitchenAt/billedAt/paidAt/closedAt, version | The single live order per table — see §5/§8 for the get-or-create + optimistic-locking mechanics that make this the source of truth all clients converge on (requirement §2, "no duplicate orders"). `recalculateTotals()` sums only non-cancelled/non-voided items; server-computed only, per requirement §50. `discountReason`/`paidAt` are Phase 4 additions. |
| `OrderItem` | id (UUID), order, menuItem, quantity (`BigDecimal`), unitPriceSnapshot (`BigDecimal`), status, priority (bool), specialInstructions, modifiersSummary, cancelReason, sentAt/acceptedAt/startedAt/readyAt/servedAt, version | `unitPriceSnapshot` freezes the menu price at add-time so later menu price edits never retroactively change an in-flight order. |
| `Tax` | id (UUID), name, code (unique), ratePercent, active, defaultRate, version | Phase 4. Referenced by `MenuItem.taxCode`; at most one active row should have `defaultRate=true`, applied to any line whose menu item has no explicit `taxCode`. |
| `Discount` | id (UUID), name, type (PERCENTAGE/FIXED_AMOUNT), value, active, version | Phase 4. A reusable preset staff can apply to a bill; applying one (preset or a one-off manual type+value) always requires `DISCOUNT_APPROVE` regardless of source. |
| `Payment` | id (UUID), order, method (CASH/CARD/UPI/WALLET/OTHER), amount, tenderedAmount, changeAmount, referenceNumber, receiptNumber (unique), receivedBy: AppUser, receivedAt, voided, voidReason, version | Phase 4. One tender against an order's bill; an order can carry several (split bill). `tenderedAmount`/`changeAmount` are CASH-only. Voiding is a soft-delete (`voided`/`voidReason`), never a hard delete, so the receipt number and audit trail survive a correction. |
| `CashMovement` | id (UUID), type (CASH_IN/CASH_OUT), amount, reason, recordedBy: AppUser, version | Phase 4's lightweight Cash Management: a manual drawer adjustment (float top-up, paid-out) that isn't a guest payment. Uses `BaseEntity.createdAt` as its timestamp. A full shift/register open-close-count workflow is later-phase scope. |
| `IdempotencyRecord` | compositeKey (PK, `Idempotency-Key` header + operation), operation, resultJson, createdAt | Backs `IdempotencyService` — replays the original response for a repeated key instead of re-executing a mutating call (§8). |
| `NumberSequence` | seriesKey (PK, e.g. `ORDER-2026-08-14`), currentValue | Pessimistic-locked counter row per day/series; `NumberGeneratorService` increments it in its own `REQUIRES_NEW` transaction to produce gap-free, collision-free order numbers under concurrent cashiers. Also backs `Payment.receiptNumber` generation (`RCPT-` prefix) since Phase 4. |
| `InventoryItem` | id (UUID), name (unique), unit, quantityOnHand, reorderThreshold, costPerUnit, active, version | Phase 5a. Standalone stock ledger, not yet wired to `MenuItem` as a recipe/bill-of-materials (that's a larger, separate piece of scope than this drop — see README). `quantityOnHand` is only ever changed via an `InventoryTransaction`, never set directly. |
| `InventoryTransaction` | id (UUID), item, type (RECEIVE/ADJUST/DEDUCT/WASTE), quantity, resultingQuantity, reason, recordedBy: AppUser, version | Phase 5a. Append-only stock movement ledger entry, mirrors `CashMovement`'s shape. `resultingQuantity` snapshots the balance immediately after this transaction so the ledger reads without recomputing a running total. |

Every entity that can be concurrently edited carries a `@Version` column (optimistic locking, see
§8).

## 5. REST API Specification — Phase 1

Base path: `/api`. All responses use the standard envelope (see §11). All endpoints except
`/api/auth/login` require a valid JWT bearer token.

| Method | Path | Purpose | Min role |
|---|---|---|---|
| POST | `/api/auth/login` | Username+password OR username+PIN login → JWT + user/role/permissions | none |
| POST | `/api/auth/logout` | Invalidate current session/device | any |
| GET | `/api/users` | List staff | MANAGER |
| POST | `/api/users` | Create staff user | ADMIN |
| PATCH | `/api/users/{id}` | Update staff user (role, active, reset PIN) | ADMIN |
| GET | `/api/roles` | List roles + permissions | MANAGER |
| PATCH | `/api/roles/{id}/permissions` | Configure a role's permissions | ADMIN |
| GET | `/api/restaurant` | Restaurant/branch/floor config | any |
| PUT | `/api/restaurant` | Update restaurant/business settings | ADMIN |
| GET | `/api/tables` | Visual table matrix (floor + status) | any |
| POST | `/api/tables` | Create table | MANAGER |
| PATCH | `/api/tables/{id}` | Update table (capacity, position, section) | MANAGER |
| GET | `/api/menu` | Categories + items | any |
| POST | `/api/menu/categories` | Create category | MANAGER |
| POST | `/api/menu/items` | Create item | MANAGER |
| PATCH | `/api/menu/items/{id}` | Update item (price, availability...) | MANAGER |
| GET | `/actuator/health`, `/actuator/info` | Observability | none (LAN-only expectation) |
| GET | `/api/orders` | List open orders (dashboard/table-matrix cross-check) | any |
| GET | `/api/orders/{id}` | Fetch one order + items, for refetch-after-conflict | any |
| POST | `/api/orders` | Get-or-create the live order for a table (requirement §2 dedup logic); supports `Idempotency-Key` | WAITER |
| POST | `/api/orders/{id}/items` | Add a line item (menuItem + qty + instructions); supports `Idempotency-Key` | `ORDER_CREATE` |
| PATCH | `/api/orders/{id}/items/{itemId}` | Update quantity/instructions on a not-yet-sent line | `ORDER_MODIFY` / `ORDER_MODIFY_OWN` |
| DELETE | `/api/orders/{id}/items/{itemId}` | Remove (pre-send) or cancel-request (post-send) a line | `ORDER_MODIFY` / `ORDER_MODIFY_OWN` |
| POST | `/api/orders/{id}/send-to-kitchen` | Push all `ADDED` lines to `SENT`, order → `SENT_TO_KITCHEN` | `ORDER_CREATE` |
| PATCH | `/api/orders/{id}/status` | Advance/cancel the order via its state machine (§10) | `ORDER_MODIFY` / `BILLING_MANAGE` depending on target |
| PATCH | `/api/orders/{id}/items/{itemId}/status` | Advance a single line's kitchen state (§10) | `KITCHEN_UPDATE` |
| GET | `/api/kitchen/queue?stationId=` | Cross-order KDS ticket queue — one ticket per order, oldest-active-item first, optionally filtered to one station | `KITCHEN_VIEW` / `KITCHEN_UPDATE` |
| GET | `/api/kitchen/stations` | List kitchen stations | `KITCHEN_VIEW` / `KITCHEN_UPDATE` / `MENU_MANAGE` |
| POST | `/api/kitchen/stations` | Create a kitchen station | `MENU_MANAGE` |
| PATCH | `/api/kitchen/stations/{id}` | Rename/reorder/activate/deactivate a station | `MENU_MANAGE` |
| GET | `/api/billing/taxes` | List tax rates | `RESTAURANT_MANAGE` / `BILLING_MANAGE` |
| POST | `/api/billing/taxes` | Create a tax rate | `RESTAURANT_MANAGE` |
| PATCH | `/api/billing/taxes/{id}` | Update a tax rate (rename/re-rate/activate/set-default) | `RESTAURANT_MANAGE` |
| GET | `/api/billing/discounts` | List discount presets | `DISCOUNT_APPROVE` / `BILLING_MANAGE` / `RESTAURANT_MANAGE` |
| POST | `/api/billing/discounts` | Create a discount preset | `RESTAURANT_MANAGE` |
| PATCH | `/api/billing/discounts/{id}` | Update a discount preset | `RESTAURANT_MANAGE` |
| GET | `/api/billing/orders/{orderId}` | Full bill for one order (subtotal/discount/tax lines/service charge/tip/total/payments/balance due) | `BILLING_MANAGE` / `ORDER_MODIFY` / `ORDER_MODIFY_OWN` |
| POST | `/api/billing/orders/{orderId}/discount` | Apply a preset or one-off discount (requires a reason) | `DISCOUNT_APPROVE` |
| POST | `/api/billing/orders/{orderId}/generate` | Freeze tax + service charge, move `BILL_REQUESTED` → `BILLED` | `BILLING_MANAGE` |
| POST | `/api/billing/orders/{orderId}/payments` | Record one tender (CASH computes change; split bill = multiple calls) | `BILLING_MANAGE` |
| POST | `/api/billing/orders/{orderId}/payments/{paymentId}/void` | Correct a mis-entered payment (soft-delete, pre-PAID only) | `BILLING_MANAGE` |
| GET | `/api/billing/orders/{orderId}/split?ways=` | Compute N even shares of the total (display-only, records nothing) | `BILLING_MANAGE` / `ORDER_MODIFY` / `ORDER_MODIFY_OWN` |
| GET | `/api/billing/orders/{orderId}/receipt` | Plain-text formatted receipt | `BILLING_MANAGE` |
| POST | `/api/billing/cash-movements` | Log a manual cash-drawer adjustment (float/paid-out) | `BILLING_MANAGE` |
| GET | `/api/billing/cash-summary?date=` | Reconcile expected cash in drawer for a day | `BILLING_MANAGE` |

Every mutating order endpoint requires the caller's last-known `version`; a mismatch returns
`409 ORDER_VERSION_CONFLICT` per §8/§11 (kitchen stations, taxes and discounts carry their own
`version` and the same conflict handling). Endpoints for `reports`, `inventory`, etc. (full list
already specified in the requirements, §58) are added module-by-module starting Phase 5 and
appended to this table rather than pre-built now. Item status transitions and order-lifecycle
status transitions (including `SERVED` → `BILL_REQUESTED`, "Request Bill") deliberately stay on the
existing `OrderController` endpoints above rather than gaining billing-specific duplicates — the
Billing screen calls those same endpoints for anything that isn't genuinely new billing logic.

## 6. WebSocket Event Specification

Transport: STOMP over WebSocket at `/ws` (SockJS fallback enabled for browser clients). Clients
authenticate the STOMP `CONNECT` frame with the same JWT used for REST.

Topics (Phase 1 wired the infrastructure + the first event; Phase 2 makes `/topic/orders` and
`/topic/tables` real, driven by `OrderService` via `WebSocketEventPublisher`; the rest are added as
their owning module lands):

| Topic | Events published | Phase |
|---|---|---|
| `/topic/notifications` | `USER_LOGGED_IN` | 1 |
| `/topic/tables` | `TABLE_STATUS_CHANGED` | 1 (infra) / **2 — implemented**, published by `OrderService.syncTableStatus` on every order/item mutation that changes table occupancy |
| `/topic/orders` | `ORDER_CREATED`, `ORDER_UPDATED`, `ORDER_ITEM_ADDED`, `ORDER_ITEM_REMOVED`, `ORDER_SENT_TO_KITCHEN`, `ORDER_STATUS_CHANGED`, `ITEM_STATUS_CHANGED`, `ORDER_PAYMENT_RECORDED` | **2 — implemented**; Phase 4 added `ORDER_PAYMENT_RECORDED` (`BillingService.recordPayment`) and reuses `ORDER_STATUS_CHANGED` for `generateBill`'s `BILL_REQUESTED`→`BILLED` move rather than inventing a separate billing event. `OrderTakingView`/`BillingView` subscribe and refetch on any event whose `entityId` matches what they're showing, rather than trusting the WS payload as authoritative (§8). |
| `/topic/kitchen` | `ORDER_SENT_TO_KITCHEN`, `ITEM_STATUS_CHANGED` | Publishers existed since Phase 2 (`OrderService.sendToKitchen`/`updateItemStatus`), unused until now. The Phase 3 KDS screen (`KitchenDisplayView`) subscribes to `/topic/orders` instead, not this topic — `updateItemStatus` also publishes `ORDER_UPDATED` there, and the KDS needs a full queue re-fetch on any relevant change regardless of which topic signaled it, so one subscription covers it. `/topic/kitchen` is left available for a future client that wants a narrower kitchen-only feed without the rest of `/topic/orders`' traffic. |
| `/topic/payments` | *(not added)* | Not needed for the same reason as `/topic/kitchen` above — `BillingService` publishes `ORDER_PAYMENT_RECORDED` to the existing `/topic/orders` instead, since `BillingView` already needs a full-bill re-fetch on any order-relevant event regardless of source topic. Left available for a future narrower payments-only feed. |
| `/topic/menu` | `MENU_AVAILABILITY_CHANGED` | 2 (item availability toggle ships with menu management) |

Every event payload carries `{ eventType, entityId, version, timestamp, correlationId }` plus a
small type-specific body — clients that only need to know "something about order X changed" can
ignore the body and re-fetch via REST, which keeps clients robust to payload evolution.

## 7. Security Model

- **AuthN**: username+password or username+PIN → Spring Security `AuthenticationManager` →
  stateless JWT (short-lived access token; refresh handled by silent re-login on the trusted LAN
  rather than a refresh-token dance, revisited later if remote access is needed).
- **AuthZ**: RBAC. `Role → Set<Permission>`. Seed roles: `ADMIN, MANAGER, CASHIER, WAITER,
  KITCHEN, VIEW_ONLY` per spec §7, but permissions are data (editable via `/api/roles`), not
  annotations hardcoded per endpoint — endpoints check `hasAuthority('PERMISSION_CODE')` where the
  permission codes are seeded, not baked into business logic.
- Passwords and PINs: BCrypt hashed, never logged (see §10 logging rules below).
- All authorization is enforced server-side; clients hide UI they can't use but the server is the
  only trust boundary.
- Table status colors, kitchen station colors etc. are **UI theme config**, not security-relevant,
  but noted here because §10 forbids hardcoding — they live in a client-side theme map keyed by
  status enum, overridable per deployment.

## 8. Concurrency Strategy

- Every mutable, multi-writer entity (`RestaurantTable`, `Order`, `OrderItem`; `Payment` etc. from
  Phase 4 on) has a JPA `@Version` column.
- Clients send the version they last read on every mutating request. A stale version causes
  Hibernate's `OptimisticLockException` (`ObjectOptimisticLockingFailureException`), translated by
  `GlobalExceptionHandler` into the standardized error envelope with `errorCode =
  ORDER_VERSION_CONFLICT` (or the entity-appropriate `_VERSION_CONFLICT` code) and HTTP 409.
  **Implemented**: `OrderTakingView.handleMutationError` checks specifically for this code and
  re-fetches + alerts rather than silently overwriting or dropping the edit.
- Idempotency: **implemented** via `IdempotencyService` + the `IdempotencyRecord` entity (§4).
  Mutating endpoints that must never double-apply (`POST /api/orders`, `POST
  /api/orders/{id}/items` today; payment/close-order endpoints from Phase 4) accept an
  `Idempotency-Key` header; the server stores `(key → result)` keyed by `composite_key =
  idempotencyKey + operation` and replays the original response for a repeated key instead of
  re-executing.
- Order-number generation is a second, narrower concurrency problem (avoiding two cashiers getting
  the same number, not avoiding double-submission of the *same* request) — solved separately by
  `NumberGeneratorService` + `NumberSequence` pessimistic-write-lock row, per §4. SQLite has no
  `SELECT ... FOR UPDATE`; its single-writer transaction serialization is relied on instead for the
  `dev` profile, documented as a known, accepted gap versus the Postgres/MySQL profiles.
- WebSocket events are advisory/"something changed, go re-fetch" signals, not the source of truth
  — this avoids a whole class of races between REST writes and WS delivery order. Confirmed by the
  Phase 2 client: `OrderTakingView` re-fetches the full order via REST on a relevant WS event
  rather than applying the WS payload as a patch.

## 9. Database Core Entities (target shape, all phases)

`Restaurant, Branch, Floor, RestaurantTable, User(AppUser), Role, Permission, Device, Shift,
MenuCategory, MenuItem, ModifierGroup, Modifier, Order, OrderItem, OrderItemModifier, Customer,
Discount, Tax, Payment, PaymentMethod, Refund, KitchenStation, KitchenTicket, InventoryItem,
StockTransaction, Reservation, WaitlistEntry, AuditLog, Notification, SyncTransaction.`

Phase 1 created the first eight; Phase 2 added `Order` and `OrderItem` (plus the standalone
`IdempotencyRecord`/`NumberSequence`); the rest are added with the phase that needs them (see §13).

## 10. Order State Machine (implemented Phase 2)

```
DRAFT → PLACED → SENT_TO_KITCHEN → ACCEPTED → PREPARING → READY → SERVED
      → BILL_REQUESTED → BILLED → PAYMENT_PENDING → PAID → CLOSED
```

Cancellation/void transitions are permission-gated side branches from any pre-`BILLED` state, never
a bare status overwrite — the backend owns the transition table (`OrderStatus.canTransitionTo`,
guarded by a `CANCELLABLE_FROM` `EnumSet`) and rejects anything not in it; no client is trusted to
enforce this. `CLOSED` and `CANCELLED` are terminal (`isTerminal()`); both reject every further
transition, including onto themselves as a "real move" (self-transition is a documented no-op).
Covered by `OrderStatusTest` (happy-path full lifecycle, no forward skips, no backward moves,
cancel-window enforcement, terminal-state rejection).

Order **item** states (independent per line, per spec §14), implemented in `OrderItemStatus`:
`ADDED → SENT → ACCEPTED → PREPARING → READY → SERVED`, with `CANCEL_REQUESTED → CANCELLED` and
`VOIDED` as permission-gated side branches — a not-yet-sent line can be cancelled directly, but a
line already sent to the kitchen must go through `CANCEL_REQUESTED` first (`CAN_REQUEST_CANCEL_FROM`
`EnumSet`), never a direct jump to `CANCELLED`. `SERVED` is terminal. Covered by
`OrderItemStatusTest`.

## 11. Standardized API Response / Error Model

Success:
```json
{ "success": true, "data": { ... }, "timestamp": "...", "correlationId": "..." }
```
Failure:
```json
{ "success": false, "errorCode": "ORDER_VERSION_CONFLICT", "message": "...", "timestamp": "...", "correlationId": "..." }
```
`correlationId` is generated per request (or read from an inbound header) and echoed into every
log line for that request, per §53.

## 12. UI Navigation Structure

**JavaFX (cashier desktop)** — Phase 1 shipped Login + Shell; Phase 2 fills in Tables and Order
Taking; Phase 4 fills in Billing; Phase 5a fills in Dashboard, Inventory and Audit Log:
```
Login (username/password or PIN; split-panel branded layout, icon-prefixed fields, a "keep me
       logged in" username-remember toggle, and an honestly-disabled "Login with Touch ID"
       affordance — see README's Phase 5a section for why it's disabled rather than faked)
 └─ Shell
     ├─ header: restaurant name, logged-in user, clock, connection status (●online/offline)
     ├─ Dashboard        DashboardView — KPI cards (today's sales/orders/payments, open order
     │                   count, occupied-table count, low-stock item count) from a single
     │                   /api/dashboard/summary call; nav item hidden without DASHBOARD_VIEW
     ├─ Tables          TableMatrixView — live grid, colored by TableTheme (status → color),
     │                   click a tile → get-or-create the order for that table (§5) → Order Taking
     ├─ Order Taking     OrderTakingView — menu panel (left) + running order (right); add/edit/
     │                   remove lines, Send to Kitchen; subscribes to /topic/orders, refetches on
     │                   any event touching the open order rather than trusting the WS payload
     ├─ Kitchen          KitchenDisplayView — one ticket per order (oldest first), big "advance
     │                   to next status" button per line, ticket border color-coded by oldest
     │                   line's age (green/amber/red); polls every 15s and refetches on any
     │                   /topic/orders event; starts/stops its poll on nav in/out (§8's "no
     │                   background work for a screen nobody's looking at")
     ├─ Billing          BillingView — order picker (left, filtered to Served/Bill Requested/
     │                   Billed/Payment Pending) + bill detail (right: subtotal, discount, tax
     │                   lines, service charge, tip, total, paid, balance due, payments list);
     │                   Request Bill / Apply Discount / Generate Bill / Record Payment / Void
     │                   Payment / Split Evenly / View Receipt actions, each hidden unless the
     │                   logged-in user holds the matching permission (§7); subscribes to
     │                   /topic/orders like the other live screens, no separate poll timeline
     ├─ Inventory        InventoryView — stock item list (low-stock items flagged) + detail panel
     │                   (Receive/Adjust/Waste/Deduct dialogs, recent movement history); write
     │                   actions hidden without INVENTORY_MANAGE, nav item hidden entirely without
     │                   at least INVENTORY_VIEW
     ├─ Audit Log        AuditLogView — read-only, paged list of AuditService's trail, optional
     │                   entity-type filter; nav item hidden without AUDIT_VIEW
     ├─ Menu             MenuManagementView — category/item catalog admin (+ New Category, + Add
     │                   Item per category, edit price/tax code/vegetarian/active, toggle
     │                   availability); nav item hidden without MENU_MANAGE. Added this round -
     │                   MenuController's create/update endpoints existed since Phase 1 with no
     │                   client screen, so a menu item could only be added by hand-editing
     │                   DataSeeder and rebuilding.
     ├─ Table Setup      TableManagementView — add tables, edit name/seating capacity/section/
     │                   active; nav item hidden without TABLE_MANAGE. Same story as Menu above
     │                   (TableController's endpoints existed, no screen did). Deliberately does
     │                   not expose table STATUS - that stays entirely order-driven
     │                   (OrderService#syncTableStatus) so this screen can't fight with it.
     ├─ Reports          ReportsView — date-range Sales Report (Today/Last 7 Days/This Month
     │                   shortcuts): total sales, order count, average order value, payment-method
     │                   breakdown table, top-selling-items table, all from GET /api/reports/sales
     │                   (aggregated from existing Payment/Order/OrderItem data); nav item hidden
     │                   without REPORT_VIEW. Added in Phase 5b.
     └─ Settings         SettingsView — restaurant profile (name/currency/GSTIN/support phone/
                         service charge) + the "require kitchen sync before Mark Order Served"
                         toggle, PUT /api/restaurant; nav item hidden without RESTAURANT_MANAGE
                         (same permission the endpoint itself requires). Added in Phase 5b.
```

`ShellView`'s header also gained a working Logout button in Phase 5b (there wasn't one before -
`SessionStore.clear()` + a fresh `StompWebSocketClient` per login, since `stop()` permanently shuts
that client's reconnect scheduler down and can't be reused across a logout/login cycle), and the
restaurant-provided ChefPay logo (`AppLogo`) now appears in the header, the login screen's brand
panel, and the app window/taskbar icon, replacing the placeholder 🍽 emoji.

Both `TableMatrixView` and `BillingView` also share a new `ReceiptPrinter` helper (view the plain-
text receipt, with a real "Print" button wired to JavaFX's `PrinterJob`/OS print dialog) - added
this round so a bill could be printed/reprinted directly from a table tile (BILL_REQUESTED/
PAYMENT_PENDING tables show a small 🖨 button), not just from the Billing screen.

**Web (tablet/waiter, kitchen, manager)** — not built yet; navigation trees already specified in
the requirements doc (§43–§45) will be followed as-is when that module starts. Deliberately
deferred out of this Phase 2 pass (scoped as "Tablet Ordering" in §13) in favor of the desktop
JavaFX order-taking flow and the shared server-side order logic every client — including the
future web client — will consume unchanged.

## 13. Development Phases & Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Spring Boot, DB, Security, Users, Roles, Restaurant, Tables, Menu, JavaFX shell, WebSocket foundation | **Done - built and run on the user's own machine** (outside this sandbox), after fixing several real issues surfaced by that run (Lombok not installed in Eclipse, a JDK-version/PATH mismatch, a missing SQLite data directory, a missing `@Param`). See README "Build status" for the full list. |
| 2 | Orders, Order Items, Table Management, Order Lifecycle, real-time sync, JavaFX order-taking | **Done - built and run on the user's own machine.** `Order`/`OrderItem` domain + state machines + unit tests, `OrderService` (get-or-create, add/update/remove item, send-to-kitchen, status transitions, idempotency, table-status sync), 9 REST endpoints, `/topic/orders` + `/topic/tables` WebSocket events, V2 Flyway migration, JavaFX `TableMatrixView` + `OrderTakingView`. That run also surfaced and led to a fix for a genuine cross-connection `REQUIRES_NEW` deadlock in `NumberGeneratorService` against SQLite's whole-file locking (see README). The **Tablet Ordering** web client (chefpay-web PWA) remains not started - out of scope for the desktop-first build. |
| 3 | KDS, Kitchen Stations, Kitchen Tickets, Item Status, Priority, Prep time | **Built and running on the user's own machine** (see README "Build status" - Phases 3-5a were written in this sandbox, which cannot reach Maven Central, then confirmed building and running for real once pulled to the user's machine). Done: `KitchenStation` entity + repository, `MenuItem.station` (optional routing), `KitchenService.listQueue` (item-status-driven cross-order ticket queue, oldest-first, optional station filter), `KitchenController` (`/api/kitchen/queue`, `/api/kitchen/stations` CRUD), V3 Flyway migration, `KitchenServiceTest` (Mockito, covers grouping/ordering/station-filtering), JavaFX `KitchenDisplayView` wired into `ShellView`. Item Status and Priority were already implemented in Phase 2 (`OrderItemStatus`, `OrderItem.priority`) and are reused as-is, not rebuilt. Deferred: **Prep-time reporting/analytics** - the raw timestamps (`sentAt`/`acceptedAt`/`startedAt`/`readyAt`/`servedAt`) have existed on `OrderItem` since Phase 2, but computing/surfacing prep-time metrics from them is a Reports-module (Phase 5) concern, not KDS. |
| 4 | Tax, Discount, Billing, Split Bill, Payments, Receipt, Cash Management | **Built and running on the user's own machine** (see Phase 3's note). Done: `Tax`/`Discount`/`Payment`/`CashMovement` entities + repositories, `Restaurant.serviceChargePercent`, `Order.discountReason`/`paidAt`, `BillingService` (discount apply/cap, tax computed proportionally per bracket on the post-discount subtotal, bill generation freezing tax+service charge, split-tender payments with CASH change calculation, payment voiding, even-split calculator, plain-text receipt, cash-drawer reconciliation), `BillingController` (16 endpoints under `/api/billing`), V4 Flyway migration, `BillingServiceTest` (Mockito, covers discount capping, proportional tax, split-payment state transitions, overpayment/void guards, split-bill rounding), JavaFX `BillingView` wired into `ShellView`'s now-enabled Billing nav item. Order-lifecycle and item-status transitions stay on the existing `OrderController` endpoints (§5) rather than gaining billing-specific duplicates. |
| 5a | Inventory, Dashboard, Audit (a coherent slice of Phase 5's full scope) | **Built and running on the user's own machine** - the JavaFX shell shows Dashboard/Tables/Kitchen/Billing/Inventory/Audit Log all live. Two real bugs surfaced by that run and fixed: the root `pom.xml`'s compiler plugin was missing `-parameters`, so every un-named `@PathVariable`/`@RequestParam` (e.g. `@PathVariable UUID id`, used throughout every controller since Phase 2) 500'd at call time with "Name for argument... not specified" even though everything compiled cleanly; and adding an item to an order threw `org.hibernate.TransientObjectException` because `Order.items`' bag-collection orphan-removal diff needs a database id for the brand-new item being appended, fixed by explicitly saving the new `OrderItem` via `orderItemRepository` before the parent `Order` save triggers that flush - see README "Build status" for the full explanation of both and their one-line/one-call fixes. Done: `InventoryItem`/`InventoryTransaction` entities + repositories (RECEIVE/ADJUST/DEDUCT/WASTE ledger, never a direct quantity edit), `InventoryService` (CRUD, low-stock listing, won't-go-negative transaction guard) + `InventoryController` (`/api/inventory/*`), `DashboardService`/`DashboardController` (`GET /api/dashboard/summary` - today's sales/orders/payments, open orders, occupied tables, low-stock count, all from existing repositories), `AuditController` (`GET /api/audit`, read-only/paged/filterable over the audit trail `AuditService` has written to since Phase 1), new `AUDIT_VIEW` permission code, V5 Flyway migration, `InventoryServiceTest` (Mockito). JavaFX `InventoryView`/`DashboardView`/`AuditLogView` wired into `ShellView`, each hidden without its permission. `LoginView` also got a UI refresh this pass (split-panel, icon-prefixed fields, "keep me logged in" username remember, disabled-with-tooltip Touch ID affordance - see README for why Touch ID is honestly non-functional rather than faked). |
| 5b | Settings, Reports (the other two Phase 5 sub-areas) + Round 4's fixes: stale paid-order reuse on table reopen, table occupied-before-any-item, table reservation, logout, kitchen-sync setting | **Drafted in this sandbox, not yet compiled/run on the user's machine** (same Maven Central caveat as 3-5a - see README "Build status"). Done: `ReportService`/`ReportController` (`GET /api/reports/sales?from=&to=` - total sales, order count, average order value, payment-method breakdown, top-selling items, all aggregated from existing `Payment`/`Order`/`OrderItem` data, gated on the already-seeded `REPORT_VIEW`), `Restaurant.requireKitchenSyncForServed` + V6 Flyway migration + `RestaurantDto`/`UpdateRestaurantRequest`/`RestaurantController` wiring, `OrderService`'s table-status handling reworked (table only turns occupied once a real item is added via `addItem`, not the instant a table is tapped in `openCreateOrder`; `TABLE_INACTIVE_STATUSES` now includes `PAID` so a reopened table after full payment starts a fresh order instead of reusing the paid one). JavaFX `SettingsView`, `ReportsView`, a `TableMatrixView` reservation quick-action (🔖 reserve from AVAILABLE, "Seat Guest Now"/"Cancel Reservation" from RESERVED - reuses the existing `PATCH /api/tables/{id}` status endpoint, no new server code), a working `ShellView` Logout button (+ a fixed latent bug where reusing one `StompWebSocketClient` across logins would have broken reconnection, since `stop()` permanently shuts its scheduler down), the ChefPay logo (`AppLogo`) wired into the header/login/window-icon, and `OrderTakingView`'s "Mark Order Served" button now actually gates on kitchen item-status progression when the new setting requires it. See README "Round 4" for full root-cause writeups. |
| 5c | Tax/Discount config UI, Cash Management (Expense/Withdrawal/Top-Up/Day End), Customers directory, expanded Reports catalog (Category/Order Type/Employee/Tip), Table View polish, online-order toggle, receipt footer, plus Round 6's payment modes/UPI QR/card/printer/cash-drawer config and Online Orders screen | **Drafted in this sandbox, not yet compiled/run on the user's machine** (same Maven Central caveat as 3-5b - see README "Build status"). Done: `SettingsView` Tax/Discount panels + online-order toggles + receipt footer field (all over Phase 4's existing `Tax`/`Discount` backend plus three new `Restaurant` columns), `BillingView`'s discount-preset picker, a new `CashManagementView` (Ledger + Day End tabs) plus one new `GET /api/billing/cash-movements?date=` endpoint, a brand-new Customers module (`Customer` entity/repository/controller/`CustomersView`, `CUSTOMER_VIEW`/`CUSTOMER_MANAGE` permissions) with `TableMatrixView` Delivery/Pick Up quick-order buttons wired to it, `TableMatrixView` elapsed-time/running-total tile badges + a status legend, and four new `ReportService`/`ReportsView` report breakdowns (V7 Flyway migration). Round 6 added on top: configurable enabled payment methods, a UPI VPA + "scan to pay" QR (`UpiQrGenerator`, `zxing`-based, no PSP/payment-gateway integration - see its javadoc), card-payment config (informational only), a configurable receipt/KOT printer name + paper width, a cash-drawer ESC/POS kick option (`ReceiptPrinter.openCashDrawer`/`printSilently`), a new Online Orders screen + `TableMatrixView` "+ Online Order" button, and `ShellView`-level auto-print of a new KOT ticket format (`ReceiptPrinter.buildKotText`) on `ORDER_CREATED` for online orders when enabled (V8 Flyway migration). See README "Round 5"/"Round 6" for the full writeups. A dedicated Reservations/Waitlist screen (beyond Phase 5b's inline table-level reservation) and the reference POS's Group/Variation/Cover-Size/Counter/Locality/Captain-Wise reports remain deferred - this app doesn't track those dimensions yet. |
| 5d | Round 7: rapid-add race/SQLite lock fix, direct-sale items, half/full portion pricing, Kitchen "Advance All", POS quantity stepper, kitchen-only restricted shell, configurable restaurant logo, category-based menu drill-down | **Drafted in this sandbox, not yet compiled/run on the user's machine** (same Maven Central caveat as 5b/5c). Done: `OrderTakingView.mutationInFlight` client-side guard + `OrderController.withLockRetry`/`KitchenController.withLockRetry` server-side retry-on-`CannotAcquireLockException` + a new `GlobalExceptionHandler` fallback for the rapid-add race (both the version-conflict popup and the raw SQLite-lock 500 reported against it); `MenuItem.directSale` (excluded from `OrderService.sendToKitchen`'s kitchen-routing, with a same-round fix so an order made up entirely of direct-sale items can still advance out of `PLACED` instead of dead-ending on `NOTHING_TO_SEND`); `MenuItem.halfPrice` + `AddItemRequest.portion` (Full/Half choice dialog, price substitution guarded server-side against a half price that isn't actually configured); `KitchenService.advanceAllItems` + `POST /api/kitchen/orders/{id}/advance-all` (batch status-advance, one call per ticket instead of one per item); `OrderTakingView.updateItemQuantity` (+/- stepper on cart lines); `ShellView.isKitchenOnly()` (permission-derived restricted shell - no new role concept, driven entirely by what Role management already grants); `Restaurant.logoImageBase64` (Settings upload/preview/remove, shown in `DashboardView`'s header, base64-inline rather than a real file-storage service); `OrderTakingView`'s two-level category-tile-then-item-tile menu drill-down (pulls categories pre-sorted by `displayOrder` from the existing `GET /api/menu` payload, no new endpoint). Also fixed during this round's review pass, pre-existing and unrelated to any single Round 7 ask: the JavaFX client's `MenuDtos.ItemDto` was missing `stationId`/`stationName` fields the server DTO already had, which made every menu load throw under Jackson's default strict parsing. New V9 Flyway migration for `menu_item.direct_sale`/`menu_item.half_price`/`restaurant.logo_image_base64`. See README "Round 7" for the full writeup. |
| 5e | Round 8: gap analysis against a reference commercial POS's screenshots, KOT Listing, Due Payment Management, Alerts/Notification inbox, Area Management, Special Note Management, Printer Listing, Menu's Item Listing table view, plus the two requested "big UI change" screens (Order Taking's persistent category sidebar, richer in-table cart header) | **Drafted in this sandbox, not yet compiled/run on the user's machine** (same Maven Central caveat as every round since 5b - see README "Build status"). Done: `OrderItem.kotNumber` (Long, assigned in `OrderService.sendToKitchen` via a new numeric `NumberGeneratorService.nextNumeric("KOT")` series alongside the existing formatted `next()`) + `KotTicketService`/`KotController` (`GET /api/kot/tickets`, groups items sharing one send-to-kitchen action into a ticket) + JavaFX `KotListingView`; `Notification` entity/repository + `NotificationService`/`NotificationController` (`GET /api/notifications`, `/unread-count`, `PATCH /{id}/read`) wired to fire on `InventoryService.recordTransaction` crossing into low stock and `OrderService.updateOrderStatus` cancelling an order, plus JavaFX `NotificationListingView` (Alerts inbox); `Area` entity/repository (seating-section catalog backing `RestaurantTable.section`'s free-text field) + `AreaController` CRUD + JavaFX `AreaManagementView`, with `TableManagementView`'s Section field becoming an editable Area-populated `ComboBox` and `TableMatrixView` gaining an Area filter dropdown over the floor grid; `SpecialNote` entity/repository (reusable order-instruction presets) + `SpecialNoteController` CRUD + JavaFX `SpecialNoteManagementView`, with a new quick-pick "Note" button per cart line in `OrderTakingView`; `PrinterProfile` entity/repository (purpose-tagged printer configs - Bill/KOT/eBill) + `PrinterProfileController` CRUD + JavaFX `PrinterProfileListingView` (persists profiles only - the actual OS print-queue picker stays client-side via the existing `ReceiptPrinter.availablePrinterNames()`, not duplicated server-side); `DuePaymentService`/`DuePaymentController` (`GET /api/due-payments` - billed orders still `UNPAID`/`PARTIALLY_PAID`, zero new schema needed) + JavaFX `DuePaymentView`; `MenuManagementView` gained a second "Item Listing" flat searchable table view mode alongside the original "By Category" grouping, reusing the exact same update/edit calls; and `OrderTakingView`'s menu panel was rebuilt from a two-level category-tile-then-item-tile drill-down into a persistent category sidebar beside the item grid, plus a richer cart-panel header (order type, live item count, most-recent KOT number badge, a "Print KOT" reprint action that never re-sends to the kitchen). One new Flyway migration (V10) added `order_item.kot_number` plus the four new tables (`area`, `special_note`, `notification`, `printer_profile`). Explicitly deferred, not silently dropped: no guest-count field (would need its own schema change), no editable order-type switcher post-creation (no endpoint for it), and the granular billing/KOT print-behavior toggles seen in the same screenshots (invoice-number format, round-off mode, "merge duplicate items on bill") - kept out of this round to keep Stage 2's schema/service changes to a manageable, non-conflicting scope. See README "Round 8" for the full writeup. |
| 6 | Offline mode, Sync engine, Backup/Restore, Printer abstraction, Recovery | Not started |
| 7 | Security/concurrency/performance/failure/migration/UI hardening | Not started |

Each phase gate is: implement → compile → test → fix → run → validate → document → proceed, per
instruction §76. Phases 1-2 cleared that gate for real, on the user's own machine, early on. This
sandbox cannot reach Maven Central (see README "Build status"), so the compile/test/run steps of
the gate could not be exercised *here* for Phases 3-5c - at the user's explicit direction ("start
coding other modules one by one... handle the few remaining bugs later"), Phase 3, Phase 4,
Phase 5a, Phase 5b, and now Phase 5c were each drafted on top of the previous working base without
waiting for an external compile confirmation. That confirmation has arrived for Phases 3-5a: the
user built and ran them on their own machine, which cleared compile+run for all three and surfaced
real bugs in succession as they clicked further in (the missing `-parameters` compiler flag, a
Hibernate `TransientObjectException` on adding an order item, the table-color bug, the
stale-paid-order-reuse bug, the occupied-before-any-item bug, and, this round, the "View Receipt"
`NullPointerException` - see the phase table rows above and README "Build status"/"Round 4"/
"Round 5"), all now fixed. **Phases 5b and 5c have not yet had that confirmation** - they're built
on the same verified base but are, like every phase before its own confirmed run, still
hand-review-only until compiled and clicked through for real. What Phases 3-5a's run has **not**
yet exercised is every endpoint end-to-end (billing, inventory transactions, and audit filtering in
particular) - see README "Next step" for what's worth clicking through next, now including all of
Phase 5b and 5c. Phase 1 and Phase 1+2 completions were each reported back to the user before the
next phase began, per the gate; the same reporting happened after Phase 3, Phase 4, Phase 5a, and
Phase 5b, and applies to this Phase 5c drop too.

### 13a. Feature ideas surfaced from a reference commercial POS

The user shared screenshots of a live commercial restaurant POS (PetPooja "KP POSS") for two
things: a login screen to restyle `LoginView` against (its split card - icon-prefixed email/
password fields, a pill-shaped "Keep me logged in" toggle, a circular Touch ID button - is what
this Phase 5a `LoginView` refresh above was built to match), and its full operations menu, as a
prompt for "anything we can add." That menu is a useful checklist of what a mature restaurant POS
eventually grows into. Mapping what it showed against what this codebase already has or has
planned, rather than build all of it now:

- **Already covered, different name** - Cash Flow/Withdrawal/Cash Top-Up → `CashMovement` +
  `BillingService.getCashSummary` (Phase 4). Menu Item On Off → `MenuItem.available` (Phase 1/2).
  Tax, Discount → Phase 4. KOTs, Table (grid with status coloring) → Phase 2/3. Inventory →
  this Phase 5a. Their Table View also shows elapsed minutes and a running total directly on each
  table tile and dedicated Delivery/Pick-Up quick-order buttons alongside dine-in - a cheap,
  concrete polish idea for `TableMatrixView` when it's next touched, not a new module.
- **Matches a module already deferred (or partly done)** - their entire Reports submenu (Category/
  Item/Sales/Order/Executive Sales/Employee/Group/Variation/Cover Size/Tip/Counter/Locality-Wise/
  Captain-Wise Summary) is a concrete report catalog beyond Phase 5b's single Sales Report - worth
  using as the literal starting checklist for growing Reports further in Phase 5c, instead of
  designing a report list from scratch. Their Customers screen matches Phase 5c's Customers.
  Online Orders maps to the online-order-platform extension point already scaffolded in
  `chefpay-plugin-api` back in Phase 1 but never implemented against a real provider. Delivery Boys
  (assigning/tracking delivery staff) is a natural add-on to that same Online Orders/delivery flow,
  not something in scope yet. Manual Sync maps to Phase 6's "Offline mode, Sync engine." Bill/KOT
  Print maps to Phase 6's "Printer abstraction" (`ReceiptPrinter`'s `PrinterJob`-based printing,
  added in Round 3, already covers ad-hoc bill printing - a dedicated ESC/POS kitchen-ticket
  printer integration is the remaining piece). Their Outlet/Settings screen (Billing display/
  calculation/print/customer config plus Online/Advance Order config) is now partly covered by
  Phase 5b's `SettingsView` - the restaurant-profile fields and the kitchen-sync toggle - with
  print-template/online-order config as the remaining gap.
- **Genuinely new ideas worth recording for a later phase** - Day End (a shift/register close +
  reconciliation report; ties into the `Shift` entity already named as a target entity in §9 but
  never built), Expense (a petty-expense log distinct from `CashMovement`'s in/out pair), Currency
  Conversion (multi-currency support - a bigger change touching `Restaurant.currencySymbol` and
  every money computation), Feedback (guest ratings/comments per order), LED customer-facing pole
  display and Dual Screen (hardware-integration features), and Language Profiles (i18n/l10n - fits
  §7's "later-phase UI hardening"). Service Renewal (SaaS license renewal) doesn't apply - this is
  a self-hosted app, not a subscription product. **Alerts inbox** and **Due Payment** (both listed
  here originally as deferred ideas) were built in Round 8 - see the 5e phase row above - along with
  Round 8's own from-screenshots additions (KOT Listing, Area Management, Special Note Management,
  Printer Listing) that weren't separately called out in this original mapping.

None of the remaining "genuinely new" bullets are built in this drop - they're recorded here as
candidate scope for Phase 6/7 so the idea isn't lost, consistent with this project's
one-coherent-slice-at-a-time phase discipline (see §13a's siblings above for why Phase 5a itself
stayed scoped to Inventory/Dashboard/Audit, and Phase 5b to Settings/Reports, rather than absorbing
all of Phase 5 at once).

### 13b. Online order aggregator integration (Swiggy/Zomato) - plan, not yet built

The user asked how ChefPay would integrate with a food-delivery aggregator like Swiggy or Zomato.
Recording the plan here rather than building it now, for two reasons: it needs a real
partner/API-access decision (see below) this session can't make, and it's naturally Phase 6+ scope
- everything it depends on (a running kitchen/billing flow) only just got confirmed working this
round.

**What already exists, unwired.** `chefpay-plugin-api` (Phase 1) already defines exactly this
extension point: `OnlineOrderProvider` (`pollNewOrders()`, `acknowledgeOrder()`,
`pushStatusUpdate()`, `syncMenu()`), the `ExternalOrderDto` shape an aggregator order would arrive
as, and the general `ChefPayPlugin` contract (id/name/config schema/health check, discovered via
either `ServiceLoader` or a Spring `@Component`). None of this is wired to anything yet - there is
no `PluginManager` in `chefpay-server`, no scheduled poller, and no Settings screen to configure or
enable a plugin. It's an interface contract from Phase 1's scaffolding, not a working system.

**The part that isn't just code.** Swiggy and Zomato don't offer a public self-serve "get an API
key and start pulling orders" integration - in practice a POS vendor has to go through their
official POS-partner program (the same route PetPooja, the earlier reference screenshots' source,
went through) to get real API/webhook access and credentials scoped to a specific restaurant
account. That's a business relationship to establish before any of this can go live against real
orders, not something achievable from this codebase alone. A middleware aggregator (a third party
that already holds those partnerships and re-exposes a unified API across Swiggy/Zomato/others) is
the common workaround smaller POS vendors use instead of applying to each platform directly - worth
evaluating against the direct-partnership route once this is prioritized.

**Recommended shape once that access exists**, following the pattern the plugin API already
implies:
1. A `@Component` (or `ServiceLoader`-registered jar) implementing `OnlineOrderProvider`, one per
   aggregator, holding that aggregator's credentials via `getConfigSchema()`/`init(PluginContext)`.
2. A scheduled poller in `chefpay-server` (`pollNewOrders()` on an interval, per the interface's
   own javadoc) that converts each `ExternalOrderDto` into a real `Order` via the *existing*
   `OrderService.openOrCreateOrder` + `addItem` calls - no new order-lifecycle code needed, an
   aggregator order becomes a normal `Order` (tagged with its source/external id so it can be
   reconciled back) and flows through the exact same kitchen ticket queue and billing screens a
   dine-in order does today.
3. `acknowledgeOrder()`/`pushStatusUpdate()` calls back out to the aggregator as the order's
   `OrderStatus`/`OrderItemStatus` change, reusing the state transitions already firing on
   `/topic/kitchen` and `/topic/orders`.
4. A Settings > Plugins screen (the disabled "Settings" nav placeholder is exactly where this
   belongs) rendering a form from `getConfigSchema()` and a "Test connection" button off
   `healthCheck()` - generic across every future plugin, not aggregator-specific UI code.
5. `Order.orderType` already has a `DELIVERY`/`TAKEAWAY`-shaped enum question to resolve against
   `ExternalOrderDto.FulfillmentType` - check `OrderType`'s current values before assuming a 1:1 map.

None of this is started. If/when there's real API access to build against, this is a self-contained
follow-up phase (call it Phase 6's "online-order integration" line) rather than a change to
anything Phase 1-5a already shipped.

## 14. Maven Project Structure

See §2 above for the module list; each module's `pom.xml` declares only the dependencies it
needs (e.g. `chefpay-core` has no Spring Web/WebSocket dependency — that lives in
`chefpay-server` — keeping the domain layer reusable and fast to test in isolation).

## 15. Round 9 — UI/UX overhaul, RBAC management UI, delivery boy, food-type indicator

See README.md's "Round 9" section for the full user-facing writeup; this section records the
architectural additions for future rounds to build against.

**New domain/schema:**
- `FoodType` enum (`VEG`/`EGG`/`NON_VEG`) on `MenuItem.foodType` (`V11__round9_food_type.sql`,
  backfilled from the pre-existing `vegetarian` boolean, which stays as an independent field - not
  derived from `foodType` - for backward compatibility with any code still reading it).
- `DeliveryBoy` entity (name/phone/active) plus `orders.delivery_boy_id` (nullable FK) and
  `restaurant.delivery_boy_feature_enabled` (`V12__round9_delivery_boy.sql`). New permission code
  `DELIVERY_MANAGE`, granted to ADMIN (via the full-permission-list role) and MANAGER.

**New endpoints:**
- `GET/POST/PATCH /api/delivery-boys` - roster CRUD, `DELIVERY_MANAGE` to write, readable by
  `ORDER_MODIFY` holders too.
- `PATCH /api/orders/{id}/delivery-boy` - assign/clear a delivery boy on an order, `ORDER_MODIFY`.
- `GET /api/permissions` - the full permission catalog (code + description), `ROLE_MANAGE` only -
  backs the new Role Management screen's checklist; previously only a role's *currently granted*
  permissions were queryable via `RoleDto`, not the full catalog.

**New JavaFX screens:** `UserManagementView`, `RoleManagementView`, `DeliveryBoysView` - all follow
`AreaManagementView`'s established list/dialog/optimistic-lock pattern exactly. None of the three
wire themselves into `ShellView`'s nav - that's centralized in `ShellView` itself (see below) to
avoid several engineers editing the same shared file concurrently.

**`ShellView` changes:**
- The left nav (`navBox`) is now a field, built once, so it can be detached (`root.setLeft(null)`)
  while `OrderTakingView` is open and reattached afterward - its internal state (which Operations
  group is expanded) survives the detach/reattach since it's the same instance, not rebuilt.
- A new collapsible "Operations" group (`buildCollapsibleGroup`) holds every back-office/admin
  screen that isn't a daily-use item - purely a nav reorganization, no permission-gate changes.
- `openOrder` now threads a second callback into `OrderTakingView` (`onBillNow`, a
  `Consumer<OrderDto>`) alongside the existing `onBack` - invoked by the new "Bill Table" button,
  routes to a new `showBillingForOrder` that calls `BillingView.focusOrder(orderId)`.

**`BillingView.focusOrder(UUID)`:** sets a `pendingFocusOrderId`, triggers `reload()`, and
auto-selects that order once the reloaded (billable-only) list arrives - a no-op past the reload if
the order isn't actually in a billable status yet (i.e. hasn't reached SERVED or later).

**`OrderTakingView` changes:** `buildMenuTile` now returns `Node` (was `Button`) - wraps the
existing tile in a small `VBox` with a food-type-colored `Region` strip on top, so `FoodType` is
visible without restructuring the tile's own click/style logic. A new bottom `HBox` (`buildBottomActionBar`)
sits in `root.setBottom(...)`; its buttons' enabled state is kept in sync with order state via a
new `updateBottomBar(order)` called from `renderOrder`, mirroring how `updatePrimaryAction` already
owns the header's single action button.

Same phase discipline as every round since Phase 5a: drafted and manually verified in this sandbox
(no real Maven Central access here - see README's sandbox caveat), not yet compiled/tested on real
infrastructure. That's the explicit next step.

## Round 10 - Operations hub UX fix, and AI feature integration

### Operations hub UX fix (post-Round-9)

Round 9 shipped "Operations" as a collapsible group inside the left nav (`buildCollapsibleGroup`).
In practice this pushed the whole sidebar into a long scroll the moment it was expanded, which read
poorly - the user asked for a reference-POS pattern instead ("a More/Operations tile grid"). Fixed
by replacing the collapsible group entirely with a single plain nav item ("Operations") that opens
a dedicated full-screen hub (`ShellView.buildOperationsHub()` / `showOperationsHub()`) - a
`FlowPane` grid of clickable cards (`opsCard(title, description, action)`), one per back-office
screen, each gated by the exact same permission checks the old sidebar rows used. Nothing about
*who* can see what changed, only *where* it's found and *how* it's presented. `buildCollapsibleGroup`
and the individual always-in-sidebar Labels for every Operations-hosted screen were removed.

### AI feature integration ("bring your own key", multi-provider)

The user asked for AI API integration wherever it could plausibly save time on a small/mid
restaurant's repetitive work, explicitly wanting features that are "not available in the market or
not widely used" rather than a token AI chatbot bolt-on. After a round of web research into 2026
restaurant-POS AI trends, six features were scoped and confirmed with the user (all six selected),
plus the provider architecture: **bring-your-own-key, multi-provider** - the restaurant pastes its
own OpenAI/Anthropic/Gemini API key into Settings; ChefPay never ships or proxies a shared key.
Explicitly deferred as separate, larger integration projects: AI voice-ordering phone agents,
WhatsApp AI ordering bots, and AI dynamic pricing (reputational risk, likely never).

**Foundation (`com.chefpay.server.ai`):**
- `AiProvider` - enum `OPENAI`/`ANTHROPIC`/`GEMINI` + a lenient `fromCode(String)` parser.
- `AiClient` - the only class that actually calls out to a provider. Built on the JDK's own
  `java.net.http.HttpClient` + Jackson `ObjectMapper`/`JsonNode` (both already transitive
  dependencies via `spring-boot-starter-web`) rather than any provider's official SDK - this
  sandbox cannot verify a brand-new Maven dependency resolves, and each provider's chat-completion
  HTTP contract is small and stable enough to hand-build directly (`chatText`/`chatWithImage`,
  request/response shape per provider hand-built from each provider's public API docs).
- `AiService` - the only class that reads `Restaurant.aiApiKey` back out and calls `AiClient`.
  Owns the three-gate enablement rule every feature shares: `aiFeaturesEnabled` (master switch) AND
  a non-blank `aiApiKey` AND that specific feature's own switch must all be true, or the request is
  rejected with a client-facing 400 (`AI_NOT_CONFIGURED`/`AI_FEATURE_DISABLED`) before anything is
  sent to a third party. Provider-level failures are translated to `AI_PROVIDER_ERROR`.
- `AiException` - internal-only failure type `AiClient` throws; never leaks past `AiService`.

**`Restaurant` entity (+`V13__round10_ai_config.sql`):** ten new columns -
`aiProvider`/`aiApiKey`/`aiModel` (plain strings; the key is NEVER returned to the client - see
`RestaurantDto.aiApiKeyConfigured`, a boolean, not the value itself) and seven booleans
(`aiFeaturesEnabled` master switch + one per feature). Mirrored into `RestaurantDto`/
`UpdateRestaurantRequest` (server + client) - null-means-unchanged throughout, except `aiApiKey`
which follows the existing `logoImageBase64` convention (blank string actually clears it, only
`null` means "leave unchanged" - the client only ever sends a real key or `""`, distinguished by a
dedicated "Clear saved API key" checkbox since the field is always rendered blank).

**New permission:** `AI_USE`, seeded onto ADMIN (full list) and MANAGER. Every AI endpoint requires
both a feature-appropriate existing permission (e.g. `MENU_MANAGE`, `REPORT_VIEW`) AND `AI_USE`.

**The six features:**

1. **AI Menu Setup from Photo/PDF** (image only this round) - `AiMenuImportController`
   (`POST /api/ai/menu-import/analyze`, `POST /api/ai/menu-import/apply`). Two-step, never one-shot:
   `analyze` sends the photo to the vision-capable provider with a strict JSON-only system prompt
   and returns a DRAFT item list; `apply` is a second, separate call that actually creates
   `MenuCategory`/`MenuItem` rows from whatever the owner reviewed/edited/deselected in the client
   (`AiToolsView`'s "Menu Import" tab). `MenuCategoryRepository.findByNameIgnoreCase` added so
   find-or-create never creates a case-only duplicate category.
2. **"Ask Your Data"** - `AiInsightsController` (`POST /api/ai/insights/chat`). Re-fetches
   `ReportService.getSalesReport` fresh on every call (defaults to the trailing 30 days) and hands
   the AI exactly that JSON as its only source of truth, with an explicit instruction not to answer
   from anything else - the "chat" can't invent a figure the restaurant's own data doesn't show.
3. **Smart reorder drafts** - `AiOpsController#reorderDraft` (`POST /api/ai/ops/reorder-draft`).
   Reads `InventoryService.listLowStock()` plus each item's recent `DEDUCT`/`WASTE`
   `InventoryTransaction` history and asks the AI to draft one ready-to-send supplier message with a
   suggested reorder quantity per item, sized to recent consumption pace where history exists.
4. **Audit anomaly flagging** - `AiOpsController#anomalyScan` (`POST /api/ai/ops/anomaly-scan`).
   New `AuditLogRepository.findByTimestampBetweenOrderByTimestampDesc` backs a scan window
   (default trailing 7 days), pre-filtered in Java to void/discount/cancel/complimentary/waste/
   delete/refund actions before being handed to the AI as a compact list - keeps prompt size
   reasonable and avoids scanning routine, uninteresting activity.
5. **AI-written menu item descriptions** - `AiMenuDescriptionController`
   (`POST /api/ai/menu/{itemId}/suggest-description`), plus a new dedicated
   `PATCH /api/menu/items/{id}/description` on `MenuController` (a separate endpoint rather than a
   13th field on the already-widely-called `UpdateItemRequest` record - see that record's javadoc).
   `MenuManagementView`'s edit dialog gained a description field + "✨ AI Suggest" button; the
   suggestion only fills the field, Save still has to be pressed, same reviewable-draft posture as
   every other AI feature here.
6. **Nightly AI summary** - `AiNightlySummaryService` (shared logic) + `AiNightlySummaryScheduler`
   (`@Scheduled(cron = "0 30 23 * * *")`, requires the new `@EnableScheduling` on
   `ChefPayServerApplication`) + `AiNightlySummaryController` (`POST /api/ai/nightly-summary/generate-now`,
   same logic, so a pilot restaurant doesn't have to wait until 11:30 PM to see what turning this on
   does). Posts a short AI-written daily recap to the existing Notification inbox
   (`NotificationService.create("AI_DAILY_SUMMARY", ...)`) - no new inbox/UI needed.

**New JavaFX screen: `AiToolsView`** - reached from a new "AI Tools" card on the Operations hub
(gated on `AI_USE`), a `TabPane` covering Menu Import, Ask Your Data, Reorder Drafts, and Anomaly
Scan (Feature E lives in `MenuManagementView` instead, being a single-item, single-field action;
Feature F's manual "Generate Now" trigger has its own tab here, its automatic nightly run needs no
UI at all). Every action button just calls its endpoint and shows the server's own message on
failure - a screen with `AI_USE` access but nothing configured yet gets a clear "turn this on in
Settings" message per feature, never a silent no-op or a confusing provider error.

**`SettingsView` changes:** a new "AI Features" section - provider `ChoiceBox`, a write-only API
key `TextField` (always rendered blank; a status `Label` shows configured/not-configured; a
"Clear saved API key" `CheckBox` is the only way to actually blank a previously-set key, since a
merely-untouched blank field means "leave unchanged"), an optional model override field, the master
`aiFeaturesEnabled` checkbox, and one checkbox per feature.

Same phase discipline as every round: drafted and manually verified in this sandbox (brace/paren
balance across every touched file, a repo-wide record-definition-vs-call-site argument-count sweep,
and a manual field-by-field re-check of every `RestaurantDto`/`UpdateRestaurantRequest`/
`UpdateItemRequest` construction call site) - this environment still has no real Maven Central
access to run `mvn compile`/`mvn test`. Please run the build and report back anything that fails.
