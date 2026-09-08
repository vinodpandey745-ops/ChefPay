import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ClipboardList, CreditCard, Loader2, Printer, Search, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { BranchSwitcher } from '@/components/common/BranchSwitcher'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Modal } from '@/components/ui/Modal'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useLiveTopics } from '@/hooks/useLiveTopics'
import { api } from '@/lib/api'
import { formatCurrency, formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import { ApiError } from '@/types/api'
import type { OrderDto, ReceiptDto } from '@/types/api'

const STATUS_TONE: Record<string, 'neutral' | 'info' | 'warning' | 'success' | 'danger' | 'brand'> = {
  DRAFT: 'neutral',
  PLACED: 'info',
  SENT_TO_KITCHEN: 'info',
  ACCEPTED: 'info',
  PREPARING: 'warning',
  READY: 'warning',
  SERVED: 'success',
  BILL_REQUESTED: 'brand',
  BILLED: 'brand',
  PAYMENT_PENDING: 'brand',
  PAID: 'success',
  CLOSED: 'neutral',
  CANCELLED: 'danger',
}

const STATUS_FILTERS = ['All Statuses', 'PLACED', 'SENT_TO_KITCHEN', 'PREPARING', 'READY', 'SERVED', 'BILLED', 'PAID', 'CANCELLED']
const TYPE_FILTERS = ['All Dining Types', 'DINE_IN', 'TAKEAWAY', 'QUICK_SERVICE', 'DELIVERY', 'PHONE_ORDER', 'ONLINE_ORDER']

// Mirrors OrderStatus#CANCELLABLE_FROM (chefpay-core) - once a bill exists (BILLED and later) a
// "cancel" is really a void/refund, deliberately out of scope here (see that enum's own javadoc),
// so the delete/void action is only ever offered for orders still in one of these statuses.
const VOIDABLE_STATUSES = new Set([
  'DRAFT',
  'PLACED',
  'SENT_TO_KITCHEN',
  'ACCEPTED',
  'PREPARING',
  'READY',
  'SERVED',
  'BILL_REQUESTED',
])

// Round 15 fix: previously the only way back to an order's Checkout / Pay flow was through the
// Tables grid, which only ever shows dine-in tables - a Takeaway/Delivery/Phone/Online order (no
// table at all) had no path back to payment once you navigated away from the POS Terminal tab it
// was opened in. This log lists every order regardless of table, so "Pay Now" here (deep-linking
// into PosTerminalPage via ?orderId=, which it already supports) is the fix for both tableless and
// dine-in orders uniformly - any order that still owes money and hasn't been voided/closed.
const PAYABLE_STATUSES = new Set([
  'DRAFT',
  'PLACED',
  'SENT_TO_KITCHEN',
  'ACCEPTED',
  'PREPARING',
  'READY',
  'SERVED',
  'BILL_REQUESTED',
  'BILLED',
  'PAYMENT_PENDING',
])

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)
}

function VoidModal({ order, onClose, onVoided }: { order: OrderDto; onClose: () => void; onVoided: () => void }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const voidOrder = useMutation({
    mutationFn: () =>
      api.patch<OrderDto>(`/orders/${order.id}/status`, { status: 'CANCELLED', reason: reason || null, version: order.version }),
    onSuccess: onVoided,
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not void this order'),
  })

  return (
    <Modal open onClose={onClose} title={`Void ${order.orderNumber}?`} widthClassName="max-w-sm">
      <div className="space-y-4">
        <p className="text-sm text-muted">
          This cancels the order and releases its table, if any. This cannot be undone from here.
        </p>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Reason (optional)</label>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={2}
            placeholder="e.g. duplicate order, customer left"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" onClick={onClose} type="button">
            Keep order
          </Button>
          <Button variant="danger" className="flex-1" disabled={voidOrder.isPending} onClick={() => voidOrder.mutate()}>
            <Trash2 className="h-4 w-4" /> {voidOrder.isPending ? 'Voiding…' : 'Void order'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

function OrderCard({ order, onRequestVoid }: { order: OrderDto; onRequestVoid: (order: OrderDto) => void }) {
  const { hasPermission } = useAuthStore()
  const navigate = useNavigate()
  const activeItems = order.items.filter((i) => i.status !== 'CANCELLED' && i.status !== 'VOIDED')
  const [printError, setPrintError] = useState<string | null>(null)
  const canModify = hasPermission('ORDER_MODIFY') || hasPermission('KITCHEN_UPDATE') || hasPermission('BILLING_MANAGE')
  const canVoid = canModify && VOIDABLE_STATUSES.has(order.status)
  const canPay = hasPermission('BILLING_MANAGE') && order.paymentStatus !== 'PAID' && PAYABLE_STATUSES.has(order.status)

  const printReceipt = useMutation({
    mutationFn: () => api.get<ReceiptDto>(`/billing/orders/${order.id}/receipt`),
    onSuccess: (receipt) => {
      setPrintError(null)
      const win = window.open('', '_blank', 'width=400,height=640')
      if (!win) {
        setPrintError('Pop-up blocked - allow pop-ups to print receipts.')
        return
      }
      win.document.write(
        `<title>${escapeHtml(receipt.orderNumber)}</title>` +
          `<pre style="font-family: 'Courier New', monospace; font-size: 12px; white-space: pre-wrap; padding: 16px;">${escapeHtml(receipt.text)}</pre>`,
      )
      win.document.close()
      win.focus()
      win.print()
    },
    onError: (err) => setPrintError(err instanceof ApiError ? err.message : 'Could not generate this receipt yet'),
  })

  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-sm font-bold text-app">{order.orderNumber}</span>
            <Badge tone={STATUS_TONE[order.status] ?? 'neutral'}>{order.status.replaceAll('_', ' ')}</Badge>
            <Badge tone={order.paymentStatus === 'PAID' ? 'success' : 'warning'}>{order.paymentStatus}</Badge>
          </div>
          <div className="mt-1 text-xs text-muted">{formatDateTime(order.updatedAt)}</div>
        </div>
        <div className="text-right">
          <div className="text-lg font-extrabold text-app">{formatCurrency(order.totalAmount)}</div>
          <div className="text-[11px] text-muted">{activeItems.length} dishes ordered</div>
        </div>
      </div>

      <div className="mt-3 grid grid-cols-3 gap-2 rounded-xl bg-app/50 px-3 py-2 text-xs">
        <div>
          <div className="font-semibold uppercase tracking-wide text-muted">Type</div>
          <div className="mt-0.5 font-medium text-app">{order.orderType.replaceAll('_', ' ')}</div>
        </div>
        <div>
          <div className="font-semibold uppercase tracking-wide text-muted">Table</div>
          <div className="mt-0.5 font-medium text-app">{order.tableName ?? '—'}</div>
        </div>
        <div>
          <div className="font-semibold uppercase tracking-wide text-muted">Customer</div>
          <div className="mt-0.5 truncate font-medium text-app">{order.customerName ?? '—'}</div>
        </div>
      </div>

      {activeItems.length > 0 && (
        <div className="mt-3">
          <div className="mb-1.5 text-[11px] font-bold uppercase tracking-wide text-muted">Items Status</div>
          <ul className="space-y-1">
            {activeItems.slice(0, 4).map((item) => (
              <li key={item.id} className="flex items-center justify-between text-xs">
                <span className="text-app">
                  {item.quantity}× {item.menuItemName}
                </span>
                <Badge tone={item.status === 'SERVED' || item.status === 'READY' ? 'success' : 'neutral'}>{item.status}</Badge>
              </li>
            ))}
            {activeItems.length > 4 && <li className="text-xs text-muted">+ {activeItems.length - 4} more</li>}
          </ul>
        </div>
      )}

      {printError && <div className="mt-2 rounded-lg bg-danger-soft px-2.5 py-1.5 text-xs text-danger">{printError}</div>}

      <div className="mt-3 flex items-center justify-between border-t border-app pt-2.5">
        <span className="text-xs text-muted">{activeItems.length} dishes ordered</span>
        <div className="flex items-center gap-1">
          {canPay && (
            <button
              type="button"
              title={order.tableName ? 'Take payment for this order' : 'Take payment (no table assigned)'}
              className="flex items-center gap-1 rounded-lg bg-brand-600 px-2.5 py-2 text-xs font-bold text-white hover:bg-brand-700 sm:py-1.5"
              onClick={() => navigate(`/pos?orderId=${order.id}`)}
            >
              <CreditCard className="h-3.5 w-3.5" /> Pay Now
            </button>
          )}
          <button
            type="button"
            title="Print / re-print receipt"
            aria-label="Print receipt"
            className="rounded-lg p-2.5 text-muted hover:bg-app disabled:opacity-50 sm:p-1.5"
            disabled={printReceipt.isPending}
            onClick={() => printReceipt.mutate()}
          >
            {printReceipt.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Printer className="h-4 w-4" />}
          </button>
          <button
            type="button"
            title={canVoid ? 'Void this order' : 'This order can no longer be voided'}
            aria-label="Void order"
            className="rounded-lg p-2.5 text-muted hover:bg-danger-soft hover:text-danger disabled:opacity-30 disabled:hover:bg-transparent disabled:hover:text-muted sm:p-1.5"
            disabled={!canVoid}
            onClick={() => onRequestVoid(order)}
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>
    </Card>
  )
}

export function OrdersLogPage() {
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('All Statuses')
  const [orderType, setOrderType] = useState('All Dining Types')
  const [voidTarget, setVoidTarget] = useState<OrderDto | null>(null)

  // Bistrodesk post-release fix: this screen never passed a branchId at all, so an unrestricted/
  // multi-branch account (e.g. Owner/Admin/Manager) always saw every branch's orders combined here
  // regardless of which physical terminal they were at - confirmed real-world bug ("orders log shows
  // other branches' data"). Defaults to this terminal's own bound branch, same convention as
  // Dashboard/Tables/POS, but - unlike Kitchen (a physical kitchen screen has no legitimate
  // cross-branch view) - this is also a genuine audit screen an Owner/Manager may deliberately want
  // to browse across every branch, so it gets the same BranchSwitcher Dashboard/Reports already use
  // rather than being hard-narrowed with no way back to "All Branches."
  const { terminal, defaultBranchId } = useAuthStore()
  const [branchId, setBranchId] = useState<string | null>(terminal?.branchId ?? defaultBranchId ?? null)

  const historyQuery = useQuery({
    queryKey: ['orders', 'history', status, branchId],
    queryFn: () =>
      api.get<OrderDto[]>('/orders/history', {
        status: status === 'All Statuses' ? undefined : status,
        limit: 200,
        branchId: branchId ?? undefined,
      }),
    refetchInterval: 20_000,
  })

  useLiveTopics(['/topic/orders'], [['orders', 'history', status, branchId]])

  const filtered = useMemo(() => {
    let orders = historyQuery.data ?? []
    if (orderType !== 'All Dining Types') orders = orders.filter((o) => o.orderType === orderType)
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      orders = orders.filter(
        (o) =>
          o.orderNumber.toLowerCase().includes(q) ||
          (o.tableName ?? '').toLowerCase().includes(q) ||
          (o.customerName ?? '').toLowerCase().includes(q),
      )
    }
    return orders
  }, [historyQuery.data, orderType, search])

  if (historyQuery.isLoading) return <FullPageSpinner label="Loading orders…" />

  return (
    <div className="space-y-4">
      <div>
        <h2 className="flex items-center gap-2 text-lg font-bold text-app">
          <ClipboardList className="h-5 w-5 text-brand-600" /> Orders Log
        </h2>
        <p className="mt-0.5 text-sm text-muted">Audit transactions, re-print receipts, and manage void logs.</p>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <div className="relative max-w-xs flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search order #, table, customer…"
            className="w-full rounded-lg border border-app bg-surface py-2 pl-9 pr-3 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <BranchSwitcher value={branchId} onChange={setBranchId} />
        <select
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          className="rounded-lg border border-app bg-surface px-3 py-2 text-sm text-app outline-none"
        >
          {STATUS_FILTERS.map((s) => (
            <option key={s} value={s}>
              {s.replaceAll('_', ' ')}
            </option>
          ))}
        </select>
        <select
          value={orderType}
          onChange={(e) => setOrderType(e.target.value)}
          className="rounded-lg border border-app bg-surface px-3 py-2 text-sm text-app outline-none"
        >
          {TYPE_FILTERS.map((t) => (
            <option key={t} value={t}>
              {t.replaceAll('_', ' ')}
            </option>
          ))}
        </select>
      </div>

      {filtered.length === 0 ? (
        <div className="flex h-64 flex-col items-center justify-center gap-2 text-muted">
          <ClipboardList className="h-8 w-8" />
          <span className="text-sm font-medium">No orders match these filters</span>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((order) => (
            <OrderCard key={order.id} order={order} onRequestVoid={setVoidTarget} />
          ))}
        </div>
      )}

      {voidTarget && (
        <VoidModal
          order={voidTarget}
          onClose={() => setVoidTarget(null)}
          onVoided={() => {
            setVoidTarget(null)
            queryClient.invalidateQueries({ queryKey: ['orders'] })
            queryClient.invalidateQueries({ queryKey: ['tables'] })
          }}
        />
      )}
    </div>
  )
}
