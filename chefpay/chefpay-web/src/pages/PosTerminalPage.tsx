import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Bike,
  CloudOff,
  CreditCard,
  Minus,
  Package,
  Pencil,
  Phone,
  Plus,
  Printer,
  Search,
  Send,
  ShoppingBag,
  ShoppingCart,
  Trash2,
  UtensilsCrossed,
  X,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { CheckoutModal } from '@/components/pos/CheckoutModal'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Modal } from '@/components/ui/Modal'
import { useLiveTopics } from '@/hooks/useLiveTopics'
import { useRestaurantConfig } from '@/hooks/useRestaurantConfig'
import { api } from '@/lib/api'
import { enqueueOutbox, listOutbox, updateOutboxEntry } from '@/lib/offlineDb'
import { useAuthStore } from '@/store/auth'
import { useSyncStore } from '@/store/sync'
import { cn, formatCurrency } from '@/lib/utils'
import { ApiError } from '@/types/api'
import type { CustomerDto, DiscountDto, MenuCategoryDto, MenuItemDto, OrderDto, OrderItemDto, TableDto } from '@/types/api'

const PRIMARY_ORDER_TYPES = [
  { value: 'DINE_IN', label: 'Dine In', icon: UtensilsCrossed },
  { value: 'TAKEAWAY', label: 'Takeaway', icon: ShoppingBag },
  { value: 'DELIVERY', label: 'Delivery', icon: Bike },
]
const MORE_ORDER_TYPES = [
  { value: 'QUICK_SERVICE', label: 'Quick Service' },
  { value: 'PHONE_ORDER', label: 'Phone Order' },
  { value: 'ONLINE_ORDER', label: 'Online Order' },
]
// POS patch: was {SERVED, BILL_REQUESTED} only, which left the Discount buttons disabled ("Available
// once order is served") for most of an order's life even though a discount is meant to be usable
// any time before the bill is frozen. Matches BillingService#DISCOUNT_EDITABLE_STATUSES exactly -
// every pre-bill, non-cancelled status - so the frontend gate and the backend's own acceptance rule
// never disagree.
const ORDER_STATUS_DISCOUNT_ELIGIBLE = new Set(['DRAFT', 'PLACED', 'SENT_TO_KITCHEN', 'ACCEPTED', 'PREPARING', 'READY', 'SERVED', 'BILL_REQUESTED'])

// Round 15/16 billing gate: an order that was never sent to the kitchen (every item still ADDED)
// can only be billed directly when the restaurant turns on "Allow billing without sending to
// kitchen" (Restaurant#kotOptionalEnabled, Settings > Operations) - the exact flag the javadoc on
// that field already describes for "when physically verified then can directly bill (without
// kitchen interference)". An order that WAS sent to kitchen must not be billable until the kitchen
// has actually served EVERY item.
//
// Round 16 bug fix: this used to key off `order.status` reaching SERVED, but that field is only
// ever advanced by CheckoutModal's own walkToBillRequested() PATCH sequence - KitchenService's
// advance-all/serve-all actions (what the Kitchen Display actually calls) only ever touch
// per-item OrderItem.status (requirement §14: independent per-line-item state), never
// Order.status. So `order.status` would sit stuck at whatever it was when the kitchen finished
// (e.g. still SENT_TO_KITCHEN) forever, and Checkout could never become clickable - exactly the
// "kitchen shows Served but I still can't bill" report. The fix reads the real source of truth
// (each item's own status) instead: eligible once every non-cancelled/voided item is SERVED. The
// backend now also enforces this for real (OrderService#updateOrderStatus, gated on
// Restaurant#requireKitchenSyncForServed) the moment CheckoutModal's walk tries to advance
// Order.status to SERVED, so this button check and the server's own rule can't drift apart.
const SERVED_OR_LATER_STATUSES = new Set(['SERVED', 'BILL_REQUESTED', 'BILLED', 'PAYMENT_PENDING', 'PAID', 'CLOSED'])
const ACTIVE_ITEM_STATUSES = (status: string) => status !== 'CANCELLED' && status !== 'VOIDED'

/**
 * Offline order-taking fix (confirmed bug report: "once server is offline client is not working,
 * user not able to take or fulfill orders" - see lib/api.ts's apiRequest javadoc for the underlying
 * OFFLINE_QUEUED mechanism this builds on top of, which already durably queues the write; what was
 * missing is entirely in this file). Every order mutation below now has, strictly additively, two
 * extra branches on top of its existing online success/failure behavior:
 *
 *   1. `order` already has a REAL server id and the write throws ApiError('OFFLINE_QUEUED') - the
 *      write is already durably queued (api.ts did that before throwing this). This file only
 *      needs to mirror the server's own effect onto the cached OrderDto so the cart doesn't look
 *      broken while the queued write waits to sync - see recomputeLocalTotals. `.version` is
 *      deliberately left untouched in this branch: another terminal could be concurrently editing
 *      this same real order right now, so faking what its next version will be is not safe (the
 *      real optimistic-concurrency check has to run for real once this replays).
 *   2. `order` is itself only a local, not-yet-synced placeholder (id prefixed LOCAL_ORDER_PREFIX -
 *      created by addItem/openOrder below, the first time their own initial `POST /orders` failed
 *      offline). Every further edit against it is queued directly via enqueueOutbox - never through
 *      api.* against the fake id, which would risk a real request reaching a real, reachable server
 *      with an invalid UUID path segment the instant connectivity returns before this order's own
 *      create has synced (a 400, not a network failure, so api.ts would NOT queue it). Bumping
 *      `.version` locally IS safe here: nothing else can be concurrently editing an order that does
 *      not exist server-side yet, so the predicted version is exactly what the server assigns once
 *      the queued writes replay in the same order they were queued in (see syncCore.ts's FIFO
 *      drain).
 *
 * Every branch is gated strictly on `isOfflineQueuedError(err)` or, for a placeholder, on
 * `isLocalOrderId(order.id)` - any real server error (validation, 409, permission) falls straight
 * through to the existing onError handler completely unchanged.
 */
const LOCAL_ORDER_PREFIX = 'local-'
const isLocalOrderId = (id: string) => id.startsWith(LOCAL_ORDER_PREFIX)

function isOfflineQueuedError(err: unknown): err is ApiError {
  return err instanceof ApiError && err.errorCode === 'OFFLINE_QUEUED'
}

function escapeKotHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)
}

/** POS patch (manual KOT print and order completion): same narrow, monospace print-window
 * convention CheckoutModal.tsx's own openPrintWindow already uses for bill receipts - fed here by
 * the server-authoritative KOT text (KotTicketService#buildManualKotText) instead of a client-side
 * reconstruction, so a manually-printed KOT matches the plain-text shape every other KOT/receipt in
 * this codebase already uses. */
function openKotPrintWindow(orderNumber: string, kotText: string): boolean {
  const win = window.open('', '_blank', 'width=400,height=640')
  if (!win) return false
  win.document.write(
    `<title>KOT - ${escapeKotHtml(orderNumber)}</title>` +
      `<pre style="font-family: 'Courier New', monospace; font-size: 12px; white-space: pre-wrap; padding: 16px;">${escapeKotHtml(kotText)}</pre>`,
  )
  win.document.close()
  win.focus()
  win.print()
  return true
}

/** Approximate re-total after a purely local edit: the real tax/discount/service-charge rules live
 * server-side (BillingService) and can't be replicated offline. This recomputes just the subtotal
 * from active line totals and folds it into whatever discount/tax/service/tip amounts were last
 * known from the server, rather than pretending to recompute those too - good enough for the cart
 * to look right while offline; the exact figures land the moment this order's next server response
 * (sync, or a manual refetch) comes back. */
function recomputeLocalTotals(order: OrderDto, items: OrderItemDto[]): OrderDto {
  const subtotal = items.filter((i) => ACTIVE_ITEM_STATUSES(i.status)).reduce((sum, i) => sum + i.lineTotal, 0)
  const totalAmount = subtotal - order.discountAmount + order.taxAmount + order.serviceChargeAmount + order.tipAmount
  return { ...order, items, subtotal, totalAmount, updatedAt: new Date().toISOString() }
}

/** Builds a brand-new client-side placeholder order (see LOCAL_ORDER_PREFIX) - used only when the
 * very first `POST /orders` for a table/cart itself fails offline, so the cashier isn't blocked
 * from starting the order at all. Every default mirrors OrderService#openOrCreateOrder's own
 * freshly-created order exactly (PLACED / UNPAID / NORMAL priority / version 0 - see Order.java's
 * @Builder.Default fields), not a guess - so it can't silently drift from what the server actually
 * creates once this placeholder's own queued `/orders` POST replays. */
function buildLocalOrder(
  id: string,
  params: { orderType: string; tableId?: string | null; tableName?: string | null; customerName?: string; customerPhone?: string },
): OrderDto {
  const now = new Date().toISOString()
  return {
    id,
    orderNumber: 'Unsynced order',
    orderType: params.orderType,
    tableId: params.tableId ?? null,
    tableName: params.tableName ?? null,
    customerName: params.customerName || null,
    customerPhone: params.customerPhone || null,
    waiterName: null,
    cashierName: null,
    status: 'PLACED',
    paymentStatus: 'UNPAID',
    priority: 'NORMAL',
    items: [],
    subtotal: 0,
    discountAmount: 0,
    taxAmount: 0,
    serviceChargeAmount: 0,
    tipAmount: 0,
    totalAmount: 0,
    notes: null,
    createdAt: now,
    updatedAt: now,
    version: 0,
    deliveryBoyId: null,
    deliveryBoyName: null,
  }
}

/** Builds the locally-shaped OrderItemDto for a menu item added while offline (either onto a fresh
 * placeholder order or onto a real order whose add-item request itself just queued) - mirrors what
 * OrderService#addItem would actually create (see MenuItemDto.directSale/halfPrice) closely enough
 * for the cart to display correctly; kotNumber/sentAt stay null exactly like a real freshly-ADDED
 * item's would. */
function buildLocalOrderItem(menuItem: MenuItemDto | undefined, menuItemId: string, isHalf: boolean): OrderItemDto {
  const unitPrice = isHalf && menuItem?.halfPrice != null ? menuItem.halfPrice : menuItem?.price ?? 0
  return {
    id: `${LOCAL_ORDER_PREFIX}item-${crypto.randomUUID()}`,
    menuItemId,
    menuItemName: menuItem?.name ?? 'Item',
    quantity: 1,
    unitPrice,
    lineTotal: unitPrice,
    status: 'ADDED',
    priority: false,
    specialInstructions: null,
    modifiersSummary: isHalf ? 'Half' : null,
    sentAt: null,
    kotNumber: null,
    directSale: menuItem?.directSale ?? false,
    version: 0,
  }
}

/** After the initial `POST /orders` throws OFFLINE_QUEUED, api.ts has already queued the create
 * into the outbox (see apiRequest's own javadoc) - but tagged as a plain create, since api.ts has
 * no idea a placeholder id even exists yet. This tags that just-queued entry after the fact with
 * `createsLocalId` (see OutboxEntry's own javadoc for what consumes it) - it's reliably the most
 * recently added entry (listOutbox's FIFO-by-id order guarantees the highest id is the latest add),
 * since nothing else in this single-threaded call stack could have queued another entry between the
 * `api.post` call above and this one. */
async function tagPendingOrderCreate(placeholderId: string): Promise<void> {
  const entries = await listOutbox()
  const createEntry = entries[entries.length - 1]
  if (createEntry && createEntry.method === 'POST' && createEntry.path === '/orders') {
    await updateOutboxEntry(createEntry.id, { createsLocalId: placeholderId })
  }
}

function MenuAndCartLoading() {
  return <div className="py-16 text-center text-sm text-muted">Loading…</div>
}

/** Discount section - uses ChefPay's real configured discount presets (GET /api/billing/discounts,
 * same list Settings manages) rather than hardcoded percentages, and only applies once the order is
 * actually eligible per the backend's own rule (BillingService#DISCOUNT_EDITABLE_STATUSES: any
 * pre-bill, non-cancelled status - i.e. any time before the bill is generated) - the improvement
 * request specifically asked for this to follow "chefpay base functionality (configurable)" rather
 * than copying the reference POS's always-on preset buttons verbatim. */
/** Bistrodesk post-release fix: {@code branchId} is new - BillingController#listDiscounts's own
 * default (every discount preset this caller can see, global + every branch's own) is deliberately
 * left unchanged for the Settings screen's discount config UI, same as Menu Editor - a manager
 * legitimately wants to see/manage every branch's presets there. But this is checkout, at one
 * physical terminal, and applying a discount preset scoped to a DIFFERENT branch here is the exact
 * same class of bug as the Menu leak - so this passes the terminal's own bound branch explicitly to
 * narrow to global + this branch's own presets only, mirroring MenuGrid's identical fix above. */
function DiscountRow({ order, branchId, onApplied }: { order: OrderDto; branchId: string | undefined; onApplied: () => void }) {
  const { hasPermission } = useAuthStore()
  const [error, setError] = useState<string | null>(null)
  const canApprove = hasPermission('DISCOUNT_APPROVE')
  const eligible = ORDER_STATUS_DISCOUNT_ELIGIBLE.has(order.status)

  const discountsQuery = useQuery({
    queryKey: ['billing', 'discounts', branchId],
    queryFn: () => api.get<DiscountDto[]>('/billing/discounts', branchId ? { branchId } : undefined),
    enabled: canApprove,
    staleTime: 60_000,
  })

  const apply = useMutation({
    mutationFn: (body: { discountId?: string; type?: string; value?: number }) =>
      api.post(`/billing/orders/${order.id}/discount`, { ...body, orderVersion: order.version }),
    onSuccess: () => {
      setError(null)
      onApplied()
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not apply discount'),
  })

  if (!canApprove) return null

  const presets = (discountsQuery.data ?? []).filter((d) => d.active);

  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between">
        <span className="text-xs font-semibold text-muted">Discount</span>
        {!eligible && <span className="text-[11px] text-muted">Not available once the bill is generated</span>}
      </div>
      <div className="flex flex-wrap gap-1.5">
        <button
          type="button"
          disabled={!eligible || apply.isPending}
          onClick={() => apply.mutate({ type: 'PERCENTAGE', value: 0 })}
          className={cn(
            'rounded-lg border px-2.5 py-1 text-xs font-semibold transition-colors disabled:opacity-40',
            order.discountAmount <= 0 ? 'border-brand-500 bg-brand-50 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200' : 'border-app text-muted hover:bg-app',
          )}
        >
          None
        </button>
        {presets.map((preset) => (
          <button
            key={preset.id}
            type="button"
            disabled={!eligible || apply.isPending}
            onClick={() => apply.mutate({ discountId: preset.id })}
            className="rounded-lg border border-app px-2.5 py-1 text-xs font-semibold text-muted transition-colors hover:bg-app disabled:opacity-40"
            title={preset.type === 'PERCENTAGE' ? `${preset.value}% off` : `${formatCurrency(preset.value)} off`}
          >
            {preset.name}
          </button>
        ))}
        {!presets.length && eligible && <span className="text-xs text-muted">No discount presets configured</span>}
      </div>
      {error && <div className="mt-1.5 text-[11px] font-medium text-danger">{error}</div>}
    </div>
  )
}

function MenuGrid({
  categories,
  loading,
  search,
  activeCategory,
  onSelectCategory,
  cartByMenuItem,
  onAddItem,
  addPending,
}: {
  categories: MenuCategoryDto[]
  loading: boolean
  search: string
  activeCategory: string
  onSelectCategory: (id: string) => void
  cartByMenuItem: Map<string, number>
  onAddItem: (menuItemId: string, portion?: 'HALF') => void
  addPending: boolean
}) {
  const active = categories.filter((c) => c.active)
  const allItems = useMemo(() => active.flatMap((c) => c.items.filter((i) => i.active && i.available)), [active])
  // Bistrodesk branch-isolation release (requirement #1): the whole card is now a single click
  // target. An item with no half price adds at full price immediately (unchanged behavior, now
  // reachable from anywhere on the card instead of one small inner button); an item with a half
  // price opens this small Full/Half popup instead of relying on two cramped inline buttons that
  // were easy to miss entirely.
  const [portionPickerItem, setPortionPickerItem] = useState<MenuItemDto | null>(null)

  const visibleItems = useMemo(() => {
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      return allItems.filter((i) => i.name.toLowerCase().includes(q))
    }
    if (activeCategory === '__all__') return allItems
    const category = active.find((c) => c.id === activeCategory)
    return category ? category.items.filter((i) => i.active && i.available) : []
  }, [allItems, active, activeCategory, search])

  if (loading) return <MenuAndCartLoading />

  return (
    <div>
      {!search.trim() && (
        <div className="mb-3 flex gap-2 overflow-x-auto pb-1">
          <button
            type="button"
            onClick={() => onSelectCategory('__all__')}
            className={cn(
              'shrink-0 rounded-full px-3 py-1.5 text-sm font-medium transition-colors',
              activeCategory === '__all__' ? 'bg-brand-600 text-white' : 'bg-surface text-muted hover:bg-app',
            )}
          >
            All Items
          </button>
          {active.map((c) => (
            <button
              key={c.id}
              type="button"
              onClick={() => onSelectCategory(c.id)}
              className={cn(
                'shrink-0 rounded-full px-3 py-1.5 text-sm font-medium transition-colors',
                activeCategory === c.id ? 'bg-brand-600 text-white' : 'bg-surface text-muted hover:bg-app',
              )}
            >
              {c.name}
            </button>
          ))}
        </div>
      )}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 xl:grid-cols-5">
        {visibleItems.map((item) => {
          const qty = cartByMenuItem.get(item.id)
          // Bug #16 fix (half/full pricing existing end-to-end on the backend) plus the Bistrodesk
          // branch-isolation release's requirement #1 fix (the click AREA): the entire card below
          // is now one button - tapping anywhere on it adds at full price directly for a plain
          // item, or opens the small Full/Half popup for one with a half price configured, instead
          // of only a small inner name/description button (full price) plus a cramped separate row
          // of two tiny buttons doing the real work.
          const hasHalf = item.halfPrice != null
          return (
            <button
              key={item.id}
              type="button"
              disabled={addPending}
              onClick={() => (hasHalf ? setPortionPickerItem(item) : onAddItem(item.id))}
              className="relative flex w-full flex-col items-start rounded-xl border border-app bg-surface p-3 text-left transition-colors hover:border-brand-400 hover:bg-brand-50 disabled:opacity-60 dark:hover:bg-brand-900/30"
            >
              {!!qty && (
                <span className="absolute -right-1.5 -top-1.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-brand-600 px-1 text-[11px] font-bold text-white">
                  {qty}
                </span>
              )}
              <span className="text-sm font-semibold text-app">{item.name}</span>
              {item.description && <span className="mt-1 line-clamp-2 text-xs text-muted">{item.description}</span>}
              {hasHalf ? (
                <span className="mt-2 border-t border-app pt-2 text-xs font-bold text-brand-600">
                  Full {formatCurrency(item.price)} · Half {formatCurrency(item.halfPrice as number)}
                </span>
              ) : (
                <span className="mt-2 border-t border-app pt-2 text-sm font-bold text-brand-600">{formatCurrency(item.price)}</span>
              )}
              {item.vegetarian && <Badge tone="success" className="mt-2">Veg</Badge>}
            </button>
          )
        })}
        {visibleItems.length === 0 && <div className="col-span-full py-8 text-center text-sm text-muted">No items match.</div>}
      </div>

      {portionPickerItem && (
        <Modal open onClose={() => setPortionPickerItem(null)} title={portionPickerItem.name} widthClassName="max-w-xs">
          <div className="space-y-2">
            <button
              type="button"
              disabled={addPending}
              onClick={() => {
                onAddItem(portionPickerItem.id)
                setPortionPickerItem(null)
              }}
              className="flex w-full items-center justify-between rounded-lg border border-app bg-app px-4 py-3 text-sm font-bold text-app transition-colors hover:border-brand-400 hover:bg-brand-50 disabled:opacity-60 dark:hover:bg-brand-900/30"
            >
              <span>Full</span>
              <span className="text-brand-600">{formatCurrency(portionPickerItem.price)}</span>
            </button>
            <button
              type="button"
              disabled={addPending}
              onClick={() => {
                onAddItem(portionPickerItem.id, 'HALF')
                setPortionPickerItem(null)
              }}
              className="flex w-full items-center justify-between rounded-lg border border-app bg-app px-4 py-3 text-sm font-bold text-app transition-colors hover:border-brand-400 hover:bg-brand-50 disabled:opacity-60 dark:hover:bg-brand-900/30"
            >
              <span>Half</span>
              <span className="text-brand-600">{formatCurrency(portionPickerItem.halfPrice as number)}</span>
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

export function PosTerminalPage() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  const [orderId, setOrderId] = useState<string | null>(null)
  const [orderType, setOrderType] = useState('DINE_IN');
  const [tableId, setTableId] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [activeCategory, setActiveCategory] = useState('__all__')
  const [phone, setPhone] = useState('')
  const [customerName, setCustomerName] = useState('')
  const [findMessage, setFindMessage] = useState<string | null>(null)
  // Bistrodesk Phase 7 (requirement #2): true right after a phone search comes back with zero
  // matches - drives the "Save New Guest" affordance, see handleFindCustomer/createGuest below.
  const [showSaveGuest, setShowSaveGuest] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [checkoutOpen, setCheckoutOpen] = useState(false)
  const [editingExisting, setEditingExisting] = useState(false)
  const [orderLocked, setOrderLocked] = useState(false)
  // Below `lg` the cart becomes a bottom-sheet overlay opened on demand (via the floating "View
  // Cart" button) instead of squeezing into an unusably narrow column - purely a mobile display
  // toggle, no effect on the order/cart data itself.
  const [mobileCartOpen, setMobileCartOpen] = useState(false)
  // POS patch (item-level remark/note): which cart line (if any) currently has its note field open
  // for editing, and the draft text for it - kept separate from OrderItemDto.specialInstructions
  // itself so typing doesn't need a round-trip per keystroke.
  const [editingNoteItemId, setEditingNoteItemId] = useState<string | null>(null)
  const [noteDraft, setNoteDraft] = useState('')

  // Bistrodesk Phase 2: a non-dine-in order now needs a branchId (Order has no table to derive one
  // from - see OrderService#openOrCreateOrder's javadoc). The terminal's OWN branch (set up once at
  // Terminal Setup, see Device#branch) is the right source of truth for "which branch is this
  // physical POS taking this order for," not the signed-in user's defaultBranchId (a user can move
  // between terminals; the terminal doesn't move between branches) - falls back to defaultBranchId
  // only for the rare case of a terminal that hasn't completed branch setup yet.
  const { terminal, defaultBranchId } = useAuthStore()
  const orderBranchId = terminal?.branchId ?? defaultBranchId ?? undefined

  // Bistrodesk branch-isolation release (requirement #7): the tables list must scope to this same
  // terminal-bound branch, not the caller's whole accessible set - otherwise a multi-branch/
  // unrestricted user's tables from every branch merge into one list with no partition, and this
  // physical till could let staff select a table that belongs to a different branch entirely.
  const tablesQuery = useQuery({
    queryKey: ['tables', orderBranchId],
    queryFn: () => api.get<TableDto[]>('/tables', orderBranchId ? { branchId: orderBranchId } : undefined),
  })
  // Bistrodesk post-release fix: MenuController's own default (an omitted branchId falls back to
  // this caller's whole accessible-branch set, `null` = unfiltered for an unrestricted/multi-branch
  // account - see that controller's javadoc) is deliberately left unchanged for the Menu Editor/
  // Settings screens, where seeing every branch's items is a legitimate whole-catalog management
  // view. This POS order-taking screen is different: a real physical till at Branch A must only ever
  // be able to ORDER shared items plus Branch A's own branch-specific items, never a Branch B-only
  // item that isn't even sold here (confirmed real-world bug: "menu items show up in each other's
  // branch"). Passing this terminal's own bound branch explicitly (same `orderBranchId` Tables above
  // already uses) narrows it correctly without touching the shared controller default.
  const menuQuery = useQuery({
    queryKey: ['menu', orderBranchId],
    queryFn: () => api.get<MenuCategoryDto[]>('/menu', orderBranchId ? { branchId: orderBranchId } : undefined),
  })
  const { data: restaurant } = useRestaurantConfig()

  // Offline order-taking fix: buildLocalOrderItem needs a menu item's name/price/directSale flag to
  // shape a locally-added line while the server is unreachable - this is the same already-cached
  // menuQuery data MenuGrid renders from, just flattened once here instead of per-add.
  const flatMenuItems = useMemo(() => (menuQuery.data ?? []).flatMap((c) => c.items), [menuQuery.data])

  const activeOrderQuery = useQuery({
    queryKey: ['orders', 'active', orderId],
    queryFn: () => api.get<OrderDto>(`/orders/${orderId}`),
    // Offline order-taking fix: a `local-...` placeholder id (see LOCAL_ORDER_PREFIX) doesn't exist
    // server-side yet - GETting it would either fail as a genuine network error (harmless, react
    // query just keeps the last-known cache entry on a failed background refetch) or, once
    // connectivity is back but before this order's own create has synced, reach the real server as
    // a pointless request with an invalid UUID path segment. Disabling the query entirely for a
    // placeholder id sidesteps both - the cache entry this screen wrote via setQueryData is the
    // only source of truth for it until syncCore.ts's remap (see store/sync.ts's offlineOrderRemap)
    // redirects this screen to the real, now-synced order id.
    enabled: !!orderId && !isLocalOrderId(orderId),
    initialData: () => (orderId ? queryClient.getQueryData<OrderDto>(['orders', 'active', orderId]) : undefined),
  })
  const order = orderId ? activeOrderQuery.data ?? null : null

  // POS patch (kitchen item status updates): subscribe this screen to the same live channel
  // KitchenPage.tsx already uses, invalidating exactly this order's own query key - kitchen staff
  // marking an individual item's status (or the existing whole-ticket advance) now reflects on the
  // cart without a manual refresh. A no-op when the current order has no id yet (nothing to
  // invalidate) - useLiveTopics itself always subscribes, matching every other screen's pattern.
  useLiveTopics(['/topic/kitchen', '/topic/orders'], orderId ? [['orders', 'active', orderId]] : [])

  // Offline order-taking fix: true once this screen's current order (real id or `local-...`
  // placeholder) has at least one optimistic, not-yet-synced local edit applied - drives the calm
  // "Saved offline" cart indicator (see the cart footer below) instead of the scarier red error
  // banner, which no longer appears for this specific case (see LOCAL_ORDER_PREFIX's javadoc above:
  // the mutations below now resolve successfully instead of rejecting for an OFFLINE_QUEUED error).
  const isOrderPendingOffline = useSyncStore((s) => (order ? s.pendingOfflineOrderIds.has(order.id) : false))
  // Offline order-taking fix: once syncCore.ts's drainOutbox has replayed this placeholder's own
  // `/orders` create and learned its real id, store/sync.ts's offlineOrderRemap carries it here -
  // this screen is the only place that can still be showing a placeholder id, so it's the one place
  // that needs to react to it and redirect.
  const remappedRealOrderId = useSyncStore((s) => (orderId ? s.offlineOrderRemap[orderId] : undefined))
  useEffect(() => {
    if (!orderId || !remappedRealOrderId) return
    useSyncStore.getState().consumeOfflineOrderRemap(orderId)
    queryClient.removeQueries({ queryKey: ['orders', 'active', orderId] })
    queryClient.invalidateQueries({ queryKey: ['orders', 'active', remappedRealOrderId] })
    setOrderId(remappedRealOrderId)
    if (searchParams.get('orderId') === orderId) {
      setSearchParams({ orderId: remappedRealOrderId }, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId, remappedRealOrderId])

  // Deep-link support: Tables' "View Order Cart / Checkout" passes ?orderId=, an available table
  // card's "Start New Order" passes ?tableId=. The orderType/tableId set here on the ?orderId= path
  // is a placeholder only ("DINE_IN" so the order-type buttons render *something* before the order
  // itself has loaded) - the effect just below corrects it to the order's real type the moment it
  // arrives, which matters for Takeaway/Delivery/Phone/Online orders deep-linked from the Orders Log's
  // "Pay Now" (those never have a table, and would otherwise be mislabeled Dine In on this screen).
  useEffect(() => {
    const paramOrderId = searchParams.get('orderId')
    const paramTableId = searchParams.get('tableId')
    if (paramOrderId) {
      setOrderId(paramOrderId)
      setEditingExisting(true)
      setOrderLocked(true)
      setOrderType('DINE_IN')
    } else if (paramTableId) {
      setOrderType('DINE_IN')
      setTableId(paramTableId)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (editingExisting && order) {
      setOrderType(order.orderType)
      setTableId(order.tableId)
      setCustomerName(order.customerName ?? '')
      setPhone(order.customerPhone ?? '')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingExisting, order?.id, order?.orderType, order?.tableId])

  const openOrder = useMutation({
    mutationFn: async (params: { orderType: string; tableId?: string; branchId?: string; customerName?: string; customerPhone?: string }) => {
      try {
        return await api.post<OrderDto>('/orders', params)
      } catch (err) {
        // Offline order-taking fix: see this file's LOCAL_ORDER_PREFIX javadoc.
        if (!isOfflineQueuedError(err)) throw err
        const placeholderId = `${LOCAL_ORDER_PREFIX}${crypto.randomUUID()}`
        await tagPendingOrderCreate(placeholderId)
        const tableName = params.tableId ? tablesQuery.data?.find((t) => t.id === params.tableId)?.name ?? null : null
        const placeholder = buildLocalOrder(placeholderId, { ...params, tableName })
        useSyncStore.getState().markOrderPendingOffline(placeholder.id)
        return placeholder
      }
    },
    onSuccess: (opened) => {
      setError(null)
      setOrderId(opened.id)
      setOrderLocked(true)
      queryClient.setQueryData(['orders', 'active', opened.id], opened)
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not open order'),
  })

  const moveTable = useMutation({
    mutationFn: async (newTableId: string) => {
      const current = order!
      const tableName = tablesQuery.data?.find((t) => t.id === newTableId)?.name ?? null
      if (isLocalOrderId(current.id)) {
        await enqueueOutbox({ method: 'PATCH', path: `/orders/${current.id}/table`, body: { tableId: newTableId, orderVersion: current.version } })
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return { ...current, tableId: newTableId, tableName, version: current.version + 1, updatedAt: new Date().toISOString() }
      }
      try {
        return await api.patch<OrderDto>(`/orders/${current.id}/table`, { tableId: newTableId, orderVersion: current.version })
      } catch (err) {
        if (!isOfflineQueuedError(err)) throw err
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return { ...current, tableId: newTableId, tableName, updatedAt: new Date().toISOString() }
      }
    },
    onSuccess: (updated) => {
      setError(null)
      queryClient.setQueryData(['orders', 'active', updated.id], updated)
      queryClient.invalidateQueries({ queryKey: ['tables'] })
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not move table'),
  })

  const attachCustomer = useMutation({
    // Bistrodesk Phase 7 (requirement #2): customerId links the real Customer directory record
    // onto the order (OrderService#updateCustomerDetails then syncs customerName/customerPhone from
    // it server-side) - omitted, this keeps the pre-Phase-7 inline-strings-only behavior for a guest
    // with no directory record.
    mutationFn: async (customerId?: string) => {
      const current = order!
      const nextName = customerName || null
      const nextPhone = phone || null
      if (isLocalOrderId(current.id)) {
        await enqueueOutbox({
          method: 'PATCH',
          path: `/orders/${current.id}/customer`,
          body: { customerName: nextName, customerPhone: nextPhone, customerId: customerId ?? undefined, orderVersion: current.version },
        })
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return { ...current, customerName: nextName, customerPhone: nextPhone, version: current.version + 1, updatedAt: new Date().toISOString() }
      }
      try {
        return await api.patch<OrderDto>(`/orders/${current.id}/customer`, {
          customerName: nextName,
          customerPhone: nextPhone,
          customerId: customerId ?? undefined,
          orderVersion: current.version,
        })
      } catch (err) {
        if (!isOfflineQueuedError(err)) throw err
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return { ...current, customerName: nextName, customerPhone: nextPhone, updatedAt: new Date().toISOString() }
      }
    },
    onSuccess: (updated) => queryClient.setQueryData(['orders', 'active', updated.id], updated),
  })

  /** Bistrodesk Phase 7 (requirement #2): the actual "quick-create" half of the phone-search flow -
   * before this phase, a search miss only patched inline customerName/customerPhone strings onto
   * the order (see handleFindCustomer below) despite the UI copy implying a guest record gets
   * saved; nothing was ever actually created. Reuses the existing real POST /api/customers endpoint
   * (same one the Customers directory page's "Register Customer" uses) rather than inventing a
   * second create path, then immediately attaches the new record via the same attachCustomer flow a
   * found match uses - so a brand-new guest and an existing one converge on identical order state. */
  const createGuest = useMutation({
    mutationFn: () => api.post<CustomerDto>('/customers', { name: customerName.trim(), phone: phone.trim() || null }),
    onSuccess: (created) => {
      setFindMessage(`Saved: ${created.name} (new guest)`)
      setShowSaveGuest(false)
      queryClient.invalidateQueries({ queryKey: ['customers'] })
      if (order) attachCustomer.mutate(created.id)
    },
    onError: (err) => setFindMessage(err instanceof ApiError ? err.message : 'Could not save this guest'),
  })

  const addItem = useMutation({
    mutationFn: async ({ menuItemId, portion }: { menuItemId: string; portion?: 'HALF' }) => {
      let current = order
      if (!current) {
        try {
          current = await api.post<OrderDto>('/orders', {
            orderType,
            tableId: orderType === 'DINE_IN' ? tableId ?? undefined : undefined,
            branchId: orderBranchId,
            customerName: customerName || undefined,
            customerPhone: phone || undefined,
          })
          setOrderId(current.id)
          setOrderLocked(true)
          queryClient.setQueryData(['orders', 'active', current.id], current)
        } catch (err) {
          // Offline order-taking fix (see LOCAL_ORDER_PREFIX's javadoc above): without this branch
          // the OFFLINE_QUEUED throw from api.post would propagate straight out of this whole
          // mutationFn, aborting BEFORE the item-add logic below ever runs - the confirmed "nothing
          // happens at all, not even a local order object" half of the bug report. A placeholder
          // order lets the cashier keep working exactly as if the order had opened normally.
          if (!isOfflineQueuedError(err)) throw err
          const placeholderId = `${LOCAL_ORDER_PREFIX}${crypto.randomUUID()}`
          await tagPendingOrderCreate(placeholderId)
          const tableName =
            orderType === 'DINE_IN' && tableId ? tablesQuery.data?.find((t) => t.id === tableId)?.name ?? null : null
          current = buildLocalOrder(placeholderId, {
            orderType,
            tableId: orderType === 'DINE_IN' ? tableId : null,
            tableName,
            customerName: customerName || undefined,
            customerPhone: phone || undefined,
          })
          setOrderId(current.id)
          setOrderLocked(true)
          queryClient.setQueryData(['orders', 'active', current.id], current)
          useSyncStore.getState().markOrderPendingOffline(current.id)
        }
      }
      // Reference-POS behavior, confirmed by hands-on testing: clicking a menu item already in the
      // cart as an ADDED (not-yet-sent) line increments that line instead of duplicating a row.
      // Bug #16 fix: this now also has to match on portion - OrderService#addItem stamps a Half
      // line's modifiersSummary as "Half" (and only that, full-price lines carry no modifiersSummary
      // at all) precisely so this can distinguish them; without this a Half tap on an item that
      // already has a Full line in the cart would incorrectly bump the Full line's quantity instead
      // of adding its own separate Half line.
      const isHalf = portion === 'HALF'
      const existing = current.items.find(
        (i) => i.menuItemId === menuItemId && i.status === 'ADDED' && (i.modifiersSummary === 'Half') === isHalf,
      )

      // Offline order-taking fix: `current` is itself an unsynced placeholder (either just created
      // above, or from an earlier offline add on this same order) - every further write against it
      // goes straight to the outbox rather than through api.*, which - the instant connectivity
      // returns but before this order's own create has synced - would reach a real, reachable
      // server with an invalid UUID path segment (a plain 400, not a network failure api.ts would
      // queue). See this file's LOCAL_ORDER_PREFIX javadoc for why bumping `.version` is safe here.
      if (isLocalOrderId(current.id)) {
        if (existing) {
          const nextQuantity = existing.quantity + 1
          await enqueueOutbox({
            method: 'PATCH',
            path: `/orders/${current.id}/items/${existing.id}`,
            body: { quantity: nextQuantity, orderVersion: current.version },
          })
          useSyncStore.getState().markOrderPendingOffline(current.id)
          const items = current.items.map((i) =>
            i.id === existing.id ? { ...i, quantity: nextQuantity, lineTotal: i.unitPrice * nextQuantity } : i,
          )
          return recomputeLocalTotals({ ...current, version: current.version + 1 }, items)
        }
        const localItem = buildLocalOrderItem(flatMenuItems.find((i) => i.id === menuItemId), menuItemId, isHalf)
        await enqueueOutbox({
          method: 'POST',
          path: `/orders/${current.id}/items`,
          body: { menuItemId, quantity: 1, portion, orderVersion: current.version },
        })
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return recomputeLocalTotals({ ...current, version: current.version + 1 }, [...current.items, localItem])
      }

      if (existing) {
        try {
          return await api.patch<OrderDto>(`/orders/${current.id}/items/${existing.id}`, {
            quantity: existing.quantity + 1,
            orderVersion: current.version,
          })
        } catch (err) {
          if (!isOfflineQueuedError(err)) throw err
          useSyncStore.getState().markOrderPendingOffline(current.id)
          const nextQuantity = existing.quantity + 1
          const items = current.items.map((i) =>
            i.id === existing.id ? { ...i, quantity: nextQuantity, lineTotal: i.unitPrice * nextQuantity } : i,
          )
          return recomputeLocalTotals(current, items)
        }
      }
      try {
        return await api.post<OrderDto>(`/orders/${current.id}/items`, { menuItemId, quantity: 1, portion, orderVersion: current.version })
      } catch (err) {
        if (!isOfflineQueuedError(err)) throw err
        useSyncStore.getState().markOrderPendingOffline(current.id)
        const localItem = buildLocalOrderItem(flatMenuItems.find((i) => i.id === menuItemId), menuItemId, isHalf)
        return recomputeLocalTotals(current, [...current.items, localItem])
      }
    },
    onSuccess: (updated) => {
      setError(null)
      queryClient.setQueryData(['orders', 'active', updated.id], updated)
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not add item'),
  })

  const applyUpdate = (updated: OrderDto) => {
    setError(null)
    queryClient.setQueryData(['orders', 'active', updated.id], updated)
  }

  const removeItem = useMutation({
    mutationFn: async (itemId: string) => {
      const current = order!
      // The trash button is only ever rendered for a still-ADDED line (see the cart list below), so
      // "remove" here always mirrors OrderService#removeOrCancelItem's ADDED branch (drop the line
      // entirely) - never its "already sent, mark CANCELLED" branch, which this UI never reaches.
      if (isLocalOrderId(current.id)) {
        await enqueueOutbox({ method: 'DELETE', path: `/orders/${current.id}/items/${itemId}`, body: { orderVersion: current.version } })
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return recomputeLocalTotals({ ...current, version: current.version + 1 }, current.items.filter((i) => i.id !== itemId))
      }
      try {
        return await api.delete<OrderDto>(`/orders/${current.id}/items/${itemId}`, { orderVersion: current.version })
      } catch (err) {
        if (!isOfflineQueuedError(err)) throw err
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return recomputeLocalTotals(current, current.items.filter((i) => i.id !== itemId))
      }
    },
    onSuccess: applyUpdate,
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not remove item'),
  })

  const updateQty = useMutation({
    mutationFn: async ({ itemId, quantity }: { itemId: string; quantity: number }) => {
      const current = order!
      const applyQty = (items: OrderItemDto[]) =>
        items.map((i) => (i.id === itemId ? { ...i, quantity, lineTotal: i.unitPrice * quantity } : i))
      if (isLocalOrderId(current.id)) {
        await enqueueOutbox({ method: 'PATCH', path: `/orders/${current.id}/items/${itemId}`, body: { quantity, orderVersion: current.version } })
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return recomputeLocalTotals({ ...current, version: current.version + 1 }, applyQty(current.items))
      }
      try {
        return await api.patch<OrderDto>(`/orders/${current.id}/items/${itemId}`, { quantity, orderVersion: current.version })
      } catch (err) {
        if (!isOfflineQueuedError(err)) throw err
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return recomputeLocalTotals(current, applyQty(current.items))
      }
    },
    onSuccess: applyUpdate,
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not update quantity'),
  })

  // POS patch (item-level remark/note): reuses the same PATCH /orders/{id}/items/{itemId} endpoint
  // updateQty already uses (OrderService#updateItem already accepts specialInstructions - see its
  // javadoc), just sending specialInstructions instead of quantity. Only ever invoked for a
  // still-ADDED line (same gate the quantity/remove controls already use below), matching
  // updateItem's own "only editable pre-kitchen" rule server-side.
  const updateNote = useMutation({
    mutationFn: async ({ itemId, specialInstructions }: { itemId: string; specialInstructions: string | null }) => {
      const current = order!
      const applyNote = (items: OrderItemDto[]) =>
        items.map((i) => (i.id === itemId ? { ...i, specialInstructions } : i))
      if (isLocalOrderId(current.id)) {
        await enqueueOutbox({
          method: 'PATCH',
          path: `/orders/${current.id}/items/${itemId}`,
          body: { specialInstructions, orderVersion: current.version },
        })
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return recomputeLocalTotals({ ...current, version: current.version + 1 }, applyNote(current.items))
      }
      try {
        return await api.patch<OrderDto>(`/orders/${current.id}/items/${itemId}`, {
          specialInstructions,
          orderVersion: current.version,
        })
      } catch (err) {
        if (!isOfflineQueuedError(err)) throw err
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return recomputeLocalTotals(current, applyNote(current.items))
      }
    },
    onSuccess: (data) => {
      applyUpdate(data)
      setEditingNoteItemId(null)
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not update item note'),
  })

  const sendToKitchen = useMutation({
    mutationFn: async () => {
      const current = order!
      // Mirrors OrderService#sendToKitchen: every still-ADDED, non-direct-sale item moves to SENT
      // (kotNumber stays null - the real KOT sequence number is only ever assigned server-side, see
      // NumberGeneratorService), and the order itself advances PLACED -> SENT_TO_KITCHEN, the only
      // transition this screen's own orders can be in before a sync (see OrderStatus#canTransitionTo).
      const advanceItems = (items: OrderItemDto[]) =>
        items.map((i) => (i.status === 'ADDED' && !i.directSale ? { ...i, status: 'SENT', sentAt: new Date().toISOString() } : i))
      const advanceStatus = (status: string) => (status === 'PLACED' ? 'SENT_TO_KITCHEN' : status)
      if (isLocalOrderId(current.id)) {
        await enqueueOutbox({ method: 'POST', path: `/orders/${current.id}/send-to-kitchen`, query: { version: current.version } })
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return {
          ...current,
          items: advanceItems(current.items),
          status: advanceStatus(current.status),
          version: current.version + 1,
          updatedAt: new Date().toISOString(),
        }
      }
      try {
        return await api.post<OrderDto>(`/orders/${current.id}/send-to-kitchen`, undefined, { version: current.version })
      } catch (err) {
        if (!isOfflineQueuedError(err)) throw err
        useSyncStore.getState().markOrderPendingOffline(current.id)
        return { ...current, items: advanceItems(current.items), status: advanceStatus(current.status), updatedAt: new Date().toISOString() }
      }
    },
    onSuccess: applyUpdate,
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not send to kitchen'),
  })

  // POS patch (manual KOT print and order completion): only reachable when this branch has opted
  // in (manualKotPrintBranchEnabled below) - an alternative to Hold/KOT above that never calls
  // send-to-kitchen, so items stay ADDED and the order never reaches the KDS. Requires the order to
  // already be synced (a still-local, not-yet-synced order has no server id the print endpoint can
  // resolve yet) - the button is disabled for that case below.
  const printKotManually = useMutation({
    mutationFn: () => api.get<string>(`/orders/${order!.id}/kot-text`),
    onSuccess: (kotText) => {
      setError(null)
      if (!openKotPrintWindow(order!.orderNumber, kotText)) {
        setError('Pop-up blocked - allow pop-ups for this site to print the KOT.')
      }
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not print this KOT'),
  })

  function decrement(itemId: string, quantity: number) {
    if (quantity <= 1) removeItem.mutate(itemId)
    else updateQty.mutate({ itemId, quantity: quantity - 1 })
  }

  function handleSelectOrderType(value: string) {
    if (orderLocked) return
    setOrderType(value)
    if (value !== 'DINE_IN') setTableId(null)
  }

  function handleSelectTable(newTableId: string) {
    if (order && orderType === 'DINE_IN') {
      moveTable.mutate(newTableId)
    }
    setTableId(newTableId)
    if (!order) {
      openOrder.mutate({ orderType: 'DINE_IN', tableId: newTableId, customerName: customerName || undefined, customerPhone: phone || undefined })
    }
  }

  async function handleFindCustomer() {
    if (!phone.trim()) {
      setFindMessage(null)
      setShowSaveGuest(false)
      return
    }
    try {
      const results = await api.get<CustomerDto[]>('/customers', { query: phone.trim() })
      if (results.length === 1) {
        setCustomerName(results[0].name)
        setFindMessage(`Found: ${results[0].name} (${results[0].visitCount} visits)`)
        setShowSaveGuest(false)
        if (order) attachCustomer.mutate(results[0].id)
      } else if (results.length > 1) {
        setFindMessage(`${results.length} matches - refine the number`)
        setShowSaveGuest(false)
        // Ambiguous - deliberately not auto-attaching any of them; a plain string edit still works
        // via the name/phone fields below, same as no match at all.
        if (order) attachCustomer.mutate(undefined)
      } else {
        // Bistrodesk Phase 7: this used to just say "will be saved as a new guest" and stop there -
        // nothing was ever actually saved. Now it's a real prompt for the "Save New Guest" action
        // just below (see createGuest), which needs a name the phone-only search doesn't provide.
        setFindMessage('No match found for this number.')
        setShowSaveGuest(true)
        if (order) attachCustomer.mutate(undefined)
      }
    } catch {
      setFindMessage(null)
      setShowSaveGuest(false)
    }
  }

  function resetToNewOrder() {
    setOrderId(null)
    setOrderLocked(false)
    setEditingExisting(false)
    setTableId(null)
    setPhone('')
    setCustomerName('')
    setFindMessage(null)
    setMobileCartOpen(false)
    setSearchParams({}, { replace: true })
  }

  const cartByMenuItem = useMemo(() => {
    const map = new Map<string, number>()
    for (const item of order?.items ?? []) {
      if (item.status === 'ADDED') map.set(item.menuItemId, (map.get(item.menuItemId) ?? 0) + item.quantity)
    }
    return map
  }, [order])

  const availableTables = (tablesQuery.data ?? []).filter((t) => t.status === 'AVAILABLE' || (order && t.id === tableId) || t.status === 'OCCUPIED')
  const pendingItems = order?.items.filter((i) => i.status === 'ADDED') ?? []
  const activeItems = order?.items.filter((i) => ACTIVE_ITEM_STATUSES(i.status)) ?? []
  const cartItemCount = activeItems.reduce((sum, i) => sum + i.quantity, 0)
  const neverSentToKitchen = activeItems.length > 0 && activeItems.every((i) => i.status === 'ADDED')
  const allItemsServed = activeItems.length > 0 && activeItems.every((i) => i.status === 'SERVED')
  const orderStatusServedOrLater = !!order && SERVED_OR_LATER_STATUSES.has(order.status)
  // POS patch (manual KOT print and order completion): this branch's own opt-in (Branches >
  // "Billing and Ordering") - see Branch#manualKotPrintEnabled's javadoc. Resolved from the same
  // restaurant config PosTerminalPage already fetches for kotOptionalEnabled above, scoped to this
  // terminal's own bound branch.
  const manualKotPrintBranchEnabled = restaurant?.branches?.find((b) => b.id === orderBranchId)?.manualKotPrintEnabled === true
  // "once this option is enabled, user can checkout and take payment immediately after taking
  // order, no need to change the status of item" - a second, independent way into the same direct-
  // billing path kotOptionalEnabled already established, so a branch doesn't also need that
  // separate install-wide toggle turned on just to use its own manual-KOT-print workflow.
  const directBillingAllowed = neverSentToKitchen && (restaurant?.kotOptionalEnabled === true || manualKotPrintBranchEnabled)
  const canCheckout = !!order && activeItems.length > 0 && (orderStatusServedOrLater || allItemsServed || directBillingAllowed)
  const checkoutHint = !order || activeItems.length === 0
    ? null
    : canCheckout
      ? null
      : neverSentToKitchen
        ? 'Send to kitchen first, or enable "Bill without kitchen" in Settings.'
        : 'Waiting for the kitchen to mark every item Served.'

  return (
    // h-full (not a hardcoded vh calc) so this fills exactly whatever height AppShell's <main> has
    // left after the topbar - correct on every breakpoint instead of assuming a fixed chrome height.
    // Below `lg` the cart column is pulled out of grid flow entirely (see its own comment below), so
    // the menu area gets the full width/height on phones and tablets; at `lg`+ it shares the row with
    // the always-visible cart column exactly as before.
    <div className="grid h-full grid-cols-1 gap-4 lg:grid-cols-[1fr_400px]">
      <div className="overflow-y-auto pr-1">
        {error && <div className="mb-3 rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        {editingExisting && order && (
          <div className="mb-3 inline-flex items-center gap-1.5 rounded-full bg-brand-50 px-3 py-1 text-xs font-bold text-brand-700 dark:bg-brand-900/40 dark:text-brand-200">
            <Pencil className="h-3.5 w-3.5" /> Editing {order.orderNumber}
          </div>
        )}

        <div className="relative mb-3">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search food menu items…"
            className="w-full rounded-xl border border-app bg-surface py-2.5 pl-9 pr-3 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>

        <MenuGrid
          categories={menuQuery.data ?? []}
          loading={menuQuery.isLoading}
          search={search}
          activeCategory={activeCategory}
          onSelectCategory={setActiveCategory}
          cartByMenuItem={cartByMenuItem}
          onAddItem={(id, portion) => addItem.mutate({ menuItemId: id, portion })}
          addPending={addItem.isPending}
        />
      </div>

      {/* Mobile/tablet backdrop for the cart sheet below - mirrors the Sidebar drawer's own
          backdrop pattern (same z-index convention: 30 for the dimmer, 40 for the panel). */}
      {mobileCartOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/40 lg:hidden"
          onClick={() => setMobileCartOpen(false)}
          aria-hidden="true"
        />
      )}

      {/* Cart panel. On `lg`+ this is the static 400px-wide second grid column, always visible, same
          as before. Below `lg` it becomes a bottom sheet instead: `fixed` pulls it out of the grid's
          normal flow entirely (so the menu column above gets the full grid area to itself), shown
          only while `mobileCartOpen` is true - opened via the floating "View Cart" button below.
          Deliberately not auto-opened on every add-to-cart tap, so adding several items in a row
          doesn't keep fighting the user for screen space; they open it when ready to review/pay. */}
      <Card
        className={cn(
          'fixed inset-x-0 bottom-0 z-40 max-h-[85vh] flex-col overflow-hidden rounded-b-none pb-[env(safe-area-inset-bottom)] sheet-slide-up',
          'lg:static lg:z-auto lg:max-h-none lg:rounded-b-2xl lg:pb-0',
          mobileCartOpen ? 'flex' : 'hidden',
          'lg:flex',
        )}
      >
        <div className="flex shrink-0 items-center justify-between border-b border-app px-3 py-2.5 lg:hidden">
          <span className="text-sm font-bold text-app">Cart{cartItemCount > 0 ? ` · ${cartItemCount} item${cartItemCount === 1 ? '' : 's'}` : ''}</span>
          <button
            type="button"
            onClick={() => setMobileCartOpen(false)}
            aria-label="Close cart"
            className="rounded-lg p-2 text-muted hover:bg-app"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="space-y-2.5 border-b border-app p-3">
          <div className="flex gap-1.5">
            {PRIMARY_ORDER_TYPES.map(({ value, label, icon: Icon }) => (
              <button
                key={value}
                type="button"
                disabled={orderLocked && orderType !== value}
                onClick={() => handleSelectOrderType(value)}
                className={cn(
                  'flex flex-1 items-center justify-center gap-1.5 rounded-lg py-2 text-xs font-bold uppercase tracking-wide transition-colors disabled:cursor-not-allowed disabled:opacity-40',
                  orderType === value ? 'bg-brand-600 text-white' : 'bg-app text-muted hover:bg-app/70',
                )}
              >
                <Icon className="h-3.5 w-3.5" /> {label}
              </button>
            ))}
          </div>
          {!orderLocked && (
            <select
              value={MORE_ORDER_TYPES.some((t) => t.value === orderType) ? orderType : ''}
              onChange={(e) => e.target.value && handleSelectOrderType(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-2 py-1 text-xs text-muted outline-none"
            >
              <option value="">More order types…</option>
              {MORE_ORDER_TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
          )}

          <div className="flex gap-2">
            {orderType === 'DINE_IN' && (
              <div className="flex flex-1 items-center gap-1.5 rounded-lg border border-app bg-app px-2">
                <UtensilsCrossed className="h-3.5 w-3.5 shrink-0 text-muted" />
                <select
                  value={tableId ?? ''}
                  onChange={(e) => handleSelectTable(e.target.value)}
                  className="w-full bg-transparent py-1.5 text-sm text-app outline-none"
                >
                  <option value="" disabled>
                    Select Table…
                  </option>
                  {availableTables.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name} (Seats: {t.seatingCapacity}) {t.status === 'OCCUPIED' && t.id !== tableId ? '[Occupied]' : ''}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="flex flex-1 items-center gap-1.5 rounded-lg border border-app bg-app px-2">
              <Phone className="h-3.5 w-3.5 shrink-0 text-muted" />
              <input
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="Phone lookup…"
                className="w-full bg-transparent py-1.5 text-sm text-app outline-none"
              />
            </div>
            <Button size="sm" variant="secondary" onClick={handleFindCustomer} type="button">
              Find
            </Button>
          </div>
          {findMessage && <div className="text-[11px] text-muted">{findMessage}</div>}
          {showSaveGuest && (
            <div className="flex items-center gap-1.5">
              <input
                value={customerName}
                onChange={(e) => setCustomerName(e.target.value)}
                placeholder="Guest name"
                className="w-full rounded-lg border border-app bg-app px-2 py-1.5 text-xs text-app outline-none focus:ring-2 focus:ring-brand-500"
              />
              <Button
                size="sm"
                variant="secondary"
                type="button"
                disabled={!customerName.trim() || createGuest.isPending}
                onClick={() => createGuest.mutate()}
              >
                {createGuest.isPending ? 'Saving…' : 'Save New Guest'}
              </Button>
            </div>
          )}
          {!showSaveGuest && customerName && (
            <div className="text-xs font-semibold text-app">Guest: {customerName}</div>
          )}
        </div>

        <div className="flex-1 space-y-2 overflow-y-auto px-3 py-3">
          {!order || order.items.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center gap-2 py-10 text-center text-sm text-muted">
              <Package className="h-8 w-8" />
              Cart is empty. Tap items on the left to add.
            </div>
          ) : (
            order.items.map((item) => (
              <div key={item.id} className="rounded-lg border border-app bg-app/40 p-2.5">
                <div className="flex items-start justify-between gap-2">
                  <span className="text-sm font-medium text-app">
                    {item.menuItemName}
                    {/* Bug #16: the only client-visible sign a line is Half rather than Full portion -
                        OrderService#addItem stamps modifiersSummary "Half" for exactly this case. */}
                    {item.modifiersSummary === 'Half' && <span className="ml-1.5 text-xs font-normal text-muted">(Half)</span>}
                  </span>
                  <span className="shrink-0 text-sm font-semibold text-app">{formatCurrency(item.lineTotal)}</span>
                </div>
                {/* POS patch (item-level remark/note): shown for every status, not just ADDED - a
                    note entered before an item was sent to the kitchen must stay visible on the cart
                    afterwards too (KOT/KDS already render it independently - see KitchenPage.tsx and
                    KotTicketService). Scoped to this exact item's own row, never another item's. */}
                {editingNoteItemId === item.id ? (
                  <div className="mt-1.5 flex items-center gap-1.5">
                    <input
                      autoFocus
                      type="text"
                      value={noteDraft}
                      onChange={(e) => setNoteDraft(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') updateNote.mutate({ itemId: item.id, specialInstructions: noteDraft.trim() || null })
                        if (e.key === 'Escape') setEditingNoteItemId(null)
                      }}
                      placeholder="e.g. less spicy, no onion, extra cheese"
                      className="w-full rounded-md border border-app bg-app px-2 py-1 text-xs text-app outline-none focus:ring-2 focus:ring-brand-500"
                    />
                    <button
                      type="button"
                      disabled={updateNote.isPending}
                      onClick={() => updateNote.mutate({ itemId: item.id, specialInstructions: noteDraft.trim() || null })}
                      className="rounded-md bg-brand-500 px-2 py-1 text-xs font-semibold text-white disabled:opacity-50"
                    >
                      Save
                    </button>
                    <button type="button" onClick={() => setEditingNoteItemId(null)} className="rounded-md px-2 py-1 text-xs text-muted hover:bg-app">
                      Cancel
                    </button>
                  </div>
                ) : (
                  item.specialInstructions && (
                    <div className="mt-1 text-xs italic text-muted">Note: {item.specialInstructions}</div>
                  )
                )}
                <div className="mt-1.5 flex items-center justify-between">
                  <Badge tone={item.status === 'ADDED' ? 'neutral' : 'info'}>{item.status}</Badge>
                  {item.status === 'ADDED' ? (
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          setEditingNoteItemId(item.id)
                          setNoteDraft(item.specialInstructions ?? '')
                        }}
                        aria-label="Add or edit note"
                        title="Add or edit note"
                        className="rounded-md bg-surface p-3 text-muted hover:bg-app sm:p-1.5"
                      >
                        <Pencil className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => decrement(item.id, item.quantity)}
                        aria-label="Decrease quantity"
                        className="rounded-md bg-surface p-3 text-muted hover:bg-app sm:p-1.5"
                      >
                        <Minus className="h-3.5 w-3.5" />
                      </button>
                      <span className="w-5 text-center text-sm font-semibold text-app">{item.quantity}</span>
                      <button
                        type="button"
                        onClick={() => updateQty.mutate({ itemId: item.id, quantity: item.quantity + 1 })}
                        aria-label="Increase quantity"
                        className="rounded-md bg-surface p-3 text-muted hover:bg-app sm:p-1.5"
                      >
                        <Plus className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => removeItem.mutate(item.id)}
                        aria-label="Remove item"
                        className="ml-1 rounded-md p-3 text-danger hover:bg-danger-soft sm:p-1.5"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  ) : (
                    <span className="text-xs text-muted">Qty {item.quantity}</span>
                  )}
                </div>
              </div>
            ))
          )}
        </div>

        <div className="space-y-2.5 border-t border-app p-3">
          {order && (
            <DiscountRow
              order={order}
              branchId={orderBranchId}
              onApplied={() => queryClient.invalidateQueries({ queryKey: ['orders', 'active', order.id] })}
            />
          )}

          {/* Offline order-taking fix: a calm, distinct "will sync" indicator - deliberately NOT the
              red error banner (see `error` above, which no longer fires for an OFFLINE_QUEUED
              mutation at all) since the cart has already been updated to reflect the change; this
              is reassurance, not a problem to react to. Same warning-tone language SyncStatusBadge
              (topbar) already establishes for "queued, not yet synced". */}
          {isOrderPendingOffline && (
            <div className="flex items-center gap-1.5 rounded-lg border border-warning/30 bg-warning-soft px-2.5 py-1.5 text-xs font-semibold text-warning">
              <CloudOff className="h-3.5 w-3.5 shrink-0" /> Saved offline - will sync automatically
            </div>
          )}

          <div className="flex justify-between text-sm text-muted">
            <span>Subtotal</span>
            <span>{formatCurrency(order?.subtotal ?? 0)}</span>
          </div>
          {!!order && order.discountAmount > 0 && (
            <div className="flex justify-between text-sm text-success">
              <span>Discount</span>
              <span>-{formatCurrency(order.discountAmount)}</span>
            </div>
          )}
          <div className="flex justify-between text-sm text-muted">
            <span>Service Charge</span>
            <span>{formatCurrency(order?.serviceChargeAmount ?? 0)}</span>
          </div>
          <div className="flex justify-between text-sm text-muted">
            <span>GST / Taxes</span>
            <span>{formatCurrency(order?.taxAmount ?? 0)}</span>
          </div>
          <div className="flex justify-between text-base font-bold text-app">
            <span>Grand Total</span>
            <span>{formatCurrency(order?.totalAmount ?? 0)}</span>
          </div>
          {/* POS patch (manual KOT print and order completion): hidden entirely unless this
              branch's own "Billing and Ordering" setting turns it on - every other branch's cart
              footer is unchanged. Printing here never calls send-to-kitchen, so items stay ADDED
              and Checkout / Pay below is immediately available via directBillingAllowed. */}
          {manualKotPrintBranchEnabled && (
            <Button
              variant="secondary"
              className="w-full"
              disabled={!pendingItems.length || !order || isLocalOrderId(order.id) || printKotManually.isPending}
              onClick={() => printKotManually.mutate()}
            >
              <Printer className="h-4 w-4" /> {printKotManually.isPending ? 'Printing…' : 'Print KOT Manually'}
            </Button>
          )}
          <div className="flex gap-2">
            <Button variant="secondary" className="flex-1" disabled={!pendingItems.length || sendToKitchen.isPending} onClick={() => sendToKitchen.mutate()}>
              <Send className="h-4 w-4" /> Hold / KOT
            </Button>
            <Button className="flex-1" disabled={!canCheckout} title={checkoutHint ?? undefined} onClick={() => setCheckoutOpen(true)}>
              <CreditCard className="h-4 w-4" /> Checkout / Pay
            </Button>
          </div>
          {checkoutHint && <div className="text-center text-[11px] text-muted">{checkoutHint}</div>}
          {order && (
            <button type="button" onClick={resetToNewOrder} className="w-full pt-1 text-center text-xs font-medium text-muted hover:text-app">
              Start a different order
            </button>
          )}
        </div>
      </Card>

      {/* Floating trigger for the cart sheet above - only below `lg` (the static column is already
          visible above that) and only once there's something in the cart to review. */}
      {!mobileCartOpen && cartItemCount > 0 && (
        <button
          type="button"
          onClick={() => setMobileCartOpen(true)}
          className="fixed inset-x-4 bottom-[calc(1rem_+_env(safe-area-inset-bottom))] z-20 flex items-center justify-between gap-3 rounded-2xl bg-brand-600 px-4 py-3.5 text-white shadow-lg transition-colors hover:bg-brand-700 lg:hidden"
        >
          <span className="flex items-center gap-2 text-sm font-bold">
            <ShoppingCart className="h-4 w-4" />
            View Cart · {cartItemCount} item{cartItemCount === 1 ? '' : 's'}
          </span>
          <span className="text-sm font-bold">{formatCurrency(order?.totalAmount ?? 0)}</span>
        </button>
      )}

      {checkoutOpen && order && (
        <CheckoutModal
          order={order}
          onClose={() => setCheckoutOpen(false)}
          onSettled={() => {
            setCheckoutOpen(false)
            queryClient.invalidateQueries({ queryKey: ['tables'] })
            queryClient.invalidateQueries({ queryKey: ['orders', 'open'] })
            resetToNewOrder()
          }}
        />
      )}
    </div>
  )
}
