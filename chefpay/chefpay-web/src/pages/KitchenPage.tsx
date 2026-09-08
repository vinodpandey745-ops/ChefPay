import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, ChefHat, Clock, Flame, MapPin, PlayCircle, StickyNote } from 'lucide-react'
import { useMemo, useState } from 'react'

import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useLiveTopics } from '@/hooks/useLiveTopics'
import { useRestaurantConfig } from '@/hooks/useRestaurantConfig'
import { api } from '@/lib/api'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import { ApiError } from '@/types/api'
import type { OrderDto, StationDto } from '@/types/api'

const ITEM_STATUS_TONE: Record<string, 'neutral' | 'info' | 'warning' | 'success'> = {
  ADDED: 'neutral',
  SENT: 'info',
  ACCEPTED: 'info',
  PREPARING: 'warning',
  READY: 'success',
  SERVED: 'success',
}

const ACTIVE_ITEM_STATUSES = new Set(['SENT', 'ACCEPTED', 'PREPARING', 'READY'])

// POS patch (kitchen item status updates): the one-step-forward chain a kitchen item can be
// individually advanced through, mirroring OrderItemStatus#canTransitionTo's own forward-only chain
// core-side. SERVED has no next step from here - a ticket's items are gone from this screen once
// served (see ACTIVE_ITEM_STATUSES above).
const NEXT_ITEM_STATUS: Record<string, string | undefined> = {
  SENT: 'ACCEPTED',
  ACCEPTED: 'PREPARING',
  PREPARING: 'READY',
  READY: 'SERVED',
}
const COMPLETED_ORDER_STATUSES = new Set(['SERVED', 'BILL_REQUESTED', 'BILLED', 'PAYMENT_PENDING', 'PAID', 'CLOSED'])

type Stage = 'NEW' | 'PREPARING' | 'READY'

/** A ticket's stage is the EARLIEST stage any of its still-in-flight items are in, so a ticket only
 * counts as "Preparing" once every item has at least started, and only reaches "Ready for Pickup"
 * once every item has. This is what makes the reference KDS's "one button moves the whole ticket"
 * pattern make sense visually - a ticket doesn't jump lanes until it genuinely belongs there. */
function ticketStage(order: OrderDto): Stage {
  const active = order.items.filter((i) => ACTIVE_ITEM_STATUSES.has(i.status))
  if (active.some((i) => i.status === 'SENT' || i.status === 'ACCEPTED')) return 'NEW'
  if (active.some((i) => i.status === 'PREPARING')) return 'PREPARING'
  return 'READY'
}

function elapsedMinutes(iso: string): number {
  return Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000))
}

function isToday(iso: string): boolean {
  const d = new Date(iso)
  const now = new Date()
  return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
}

const STAGE_META: Record<Stage, { title: string; accent: string; badge: string }> = {
  NEW: { title: 'New Tickets', accent: 'border-t-info', badge: 'bg-info-soft text-info' },
  PREPARING: { title: 'Preparing', accent: 'border-t-warning', badge: 'bg-warning-soft text-warning' },
  READY: { title: 'Ready for Pickup', accent: 'border-t-success', badge: 'bg-success-soft text-success' },
}

function TicketCard({ order }: { order: OrderDto }) {
  const queryClient = useQueryClient()
  const stage = ticketStage(order)
  const activeItems = order.items.filter((i) => !['CANCELLED', 'VOIDED', 'ADDED'].includes(i.status))
  const minutes = elapsedMinutes(order.createdAt)
  const urgent = minutes >= 15
  const hasPriority = order.items.some((i) => i.priority)
  const [error, setError] = useState<string | null>(null)
  // POS patch (kitchen item status updates): Restaurant#itemLevelKitchenStatusEnabled - off by
  // default, so this card's per-item controls stay hidden and the existing whole-ticket "advance
  // all" flow below is the only thing rendered, exactly as before this patch.
  const { data: restaurant } = useRestaurantConfig()
  const itemStatusEnabled = !!restaurant?.itemLevelKitchenStatusEnabled

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['kitchen', 'queue'] })

  // Reuses the existing PATCH /orders/{id}/items/{itemId}/status endpoint (OrderController /
  // OrderService#updateItemStatus) - the same one advance-all's server-side counterpart is built
  // on, just scoped to one item. /topic/kitchen + /topic/orders already fire from this endpoint
  // (see OrderService), so POS's own useLiveTopics subscription (PosTerminalPage.tsx) picks this up
  // without any extra plumbing.
  const advanceItem = useMutation({
    mutationFn: ({ itemId, status }: { itemId: string; status: string }) =>
      api.patch<OrderDto>(`/orders/${order.id}/items/${itemId}/status`, { status, reason: null, orderVersion: order.version }),
    onSuccess: () => {
      setError(null)
      invalidate()
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not update this item'),
  })

  // Bug fix: every stage's CTA advances items exactly one step via /advance-all - READY items move
  // straight to SERVED with this same call (that's the next step in the OrderItemStatus chain, see
  // KitchenService#advanceAllItems). The previous "Complete / Serve" button called /serve-all
  // instead, which the server hard-gates behind `Restaurant.kitchenServiceMode == "SIMPLE"` (see
  // KitchenService#serveAllItems's javadoc) and rejects with a 400 otherwise - since that response
  // was never surfaced (no onError handler existed), a ticket in Ready for Pickup looked "stuck"
  // forever instead of visibly failing. advance-all has no such gate, so this fixes it for every
  // restaurant regardless of that setting.
  const advanceAll = useMutation({
    mutationFn: () => api.post<OrderDto>(`/kitchen/orders/${order.id}/advance-all`, undefined, { version: order.version }),
    onSuccess: () => {
      setError(null)
      invalidate()
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not update this ticket'),
  })

  const cta =
    stage === 'READY'
      ? { label: 'Complete / Serve', icon: CheckCircle2, tone: 'bg-success hover:opacity-90' }
      : stage === 'PREPARING'
        ? { label: 'Mark Ready', icon: ChefHat, tone: 'bg-warning hover:opacity-90' }
        : { label: 'Start Cooking', icon: PlayCircle, tone: 'bg-success hover:opacity-90' }

  return (
    <div className={cn('rounded-2xl border border-app border-t-4 bg-surface p-3.5 shadow-sm', STAGE_META[stage].accent, urgent && 'ring-1 ring-danger/40')}>
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="flex items-center gap-1.5 text-sm font-bold text-app">
            {hasPriority && <Flame className="h-3.5 w-3.5 text-danger" />}
            {order.orderNumber}
          </div>
          <div className="mt-0.5 flex items-center gap-2 text-[11px] font-medium text-muted">
            <span className="rounded bg-app px-1.5 py-0.5 uppercase">{order.orderType.replaceAll('_', ' ')}</span>
            {order.tableName && (
              <span className="flex items-center gap-0.5">
                <MapPin className="h-3 w-3" /> {order.tableName}
              </span>
            )}
          </div>
        </div>
        <div className={cn('flex items-center gap-1 text-xs font-semibold', urgent ? 'text-danger' : 'text-muted')}>
          <Clock className="h-3.5 w-3.5" />
          {minutes}m ago
        </div>
      </div>

      <ul className="mt-3 space-y-1.5">
        {activeItems.map((item) => (
          <li key={item.id} className="text-sm">
            <div className="flex items-center justify-between gap-2">
              <span className="flex items-center gap-1.5 text-app">
                <span className="font-medium">{item.quantity}×</span> {item.menuItemName}
              </span>
              <Badge tone={ITEM_STATUS_TONE[item.status] ?? 'neutral'}>{item.status}</Badge>
            </div>
            {/* POS patch (item-level remark/note): scoped to this exact item only - never rendered
                against any other line on this ticket - and visually distinct from the order-level
                `order.notes` block below (amber/warning tone vs. plain muted). */}
            {item.specialInstructions && (
              <div className="mt-0.5 flex items-start gap-1 pl-0.5 text-xs font-medium text-warning">
                <StickyNote className="mt-0.5 h-3 w-3 shrink-0" />
                <span>{item.specialInstructions}</span>
              </div>
            )}
            {/* POS patch (kitchen item status updates): only rendered when the restaurant has
                turned this on; hidden entirely otherwise so the ticket looks exactly as it did
                before this patch. Advances just this one item, independently of the ticket-level
                "advance all" action below. */}
            {itemStatusEnabled && NEXT_ITEM_STATUS[item.status] && (
              <button
                type="button"
                disabled={advanceItem.isPending}
                onClick={() => advanceItem.mutate({ itemId: item.id, status: NEXT_ITEM_STATUS[item.status] as string })}
                className="mt-0.5 rounded-md border border-app px-1.5 py-0.5 text-[11px] font-semibold text-muted transition-colors hover:bg-app disabled:opacity-50"
              >
                Mark {NEXT_ITEM_STATUS[item.status]}
              </button>
            )}
          </li>
        ))}
        {activeItems.length === 0 && <li className="text-sm text-muted">No active items</li>}
      </ul>

      {order.notes && <div className="mt-2 rounded-lg bg-app px-2.5 py-1.5 text-xs text-muted">{order.notes}</div>}

      {error && <div className="mt-2 rounded-lg bg-danger-soft px-2.5 py-1.5 text-xs font-medium text-danger">{error}</div>}

      <Button
        className={cn('mt-3 w-full text-white', cta.tone)}
        onClick={() => advanceAll.mutate()}
        disabled={advanceAll.isPending}
      >
        <cta.icon className="h-4 w-4" /> {advanceAll.isPending ? 'Updating…' : cta.label}
      </Button>
    </div>
  )
}

export function KitchenPage() {
  const [stationId, setStationId] = useState<string | undefined>(undefined)

  // Bistrodesk post-release fix: this screen never passed a branchId to either endpoint below, so
  // an unrestricted/multi-branch account (e.g. Owner/Admin/Manager) at any physical kitchen screen
  // saw every branch's tickets and "completed today" orders combined (confirmed real-world bug:
  // "kitchen display shows other branches' orders") - the server-side default now narrows to this
  // same branch on its own (see KitchenService#listQueue/OrderController's own javadocs), but passing
  // it explicitly here too keeps the query key correctly scoped per branch and matches the same
  // `terminal?.branchId ?? defaultBranchId` convention every other POS-facing screen already uses.
  const { terminal, defaultBranchId } = useAuthStore()
  const branchId = terminal?.branchId ?? defaultBranchId ?? undefined

  const stationsQuery = useQuery({
    queryKey: ['kitchen', 'stations'],
    queryFn: () => api.get<StationDto[]>('/kitchen/stations'),
  })

  const queueQuery = useQuery({
    queryKey: ['kitchen', 'queue', branchId, stationId],
    queryFn: () => api.get<OrderDto[]>('/kitchen/queue', { branchId, stationId }),
    refetchInterval: 15_000,
  })

  const historyQuery = useQuery({
    queryKey: ['orders', 'history', 'kitchen-completed', branchId],
    queryFn: () => api.get<OrderDto[]>('/orders/history', { limit: 80, branchId }),
    refetchInterval: 30_000,
  })

  useLiveTopics(['/topic/kitchen', '/topic/orders'], [['kitchen', 'queue'], ['orders', 'history', 'kitchen-completed']])

  const orders = queueQuery.data ?? []
  const columns = useMemo(() => {
    const grouped: Record<Stage, OrderDto[]> = { NEW: [], PREPARING: [], READY: [] }
    for (const order of orders) grouped[ticketStage(order)].push(order)
    return grouped
  }, [orders])

  const completedToday = useMemo(
    () => (historyQuery.data ?? []).filter((o) => isToday(o.createdAt) && COMPLETED_ORDER_STATUSES.has(o.status)),
    [historyQuery.data],
  )

  if (queueQuery.isLoading) return <FullPageSpinner label="Loading kitchen queue…" />

  return (
    <div className="space-y-4">
      {!!stationsQuery.data?.length && (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => setStationId(undefined)}
            className={cn(
              'rounded-full px-3 py-1.5 text-sm font-medium transition-colors',
              stationId === undefined ? 'bg-brand-600 text-white' : 'bg-surface text-muted hover:bg-app',
            )}
          >
            All Stations
          </button>
          {stationsQuery.data.map((s) => (
            <button
              key={s.id}
              type="button"
              onClick={() => setStationId(s.id)}
              className={cn(
                'rounded-full px-3 py-1.5 text-sm font-medium transition-colors',
                stationId === s.id ? 'bg-brand-600 text-white' : 'bg-surface text-muted hover:bg-app',
              )}
            >
              {s.name}
            </button>
          ))}
        </div>
      )}

      {orders.length === 0 && completedToday.length === 0 ? (
        <div className="flex h-64 flex-col items-center justify-center gap-2 text-muted">
          <ChefHat className="h-8 w-8" />
          <span className="text-sm font-medium">Kitchen queue is empty</span>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
          {(['NEW', 'PREPARING', 'READY'] as Stage[]).map((stage) => (
            <div key={stage} className="space-y-3">
              <div className="flex items-center justify-between px-1">
                <h3 className="text-sm font-bold text-app">{STAGE_META[stage].title}</h3>
                <span className={cn('rounded-full px-2 py-0.5 text-xs font-bold', STAGE_META[stage].badge)}>
                  {columns[stage].length}
                </span>
              </div>
              <div className="space-y-3">
                {columns[stage].map((order) => (
                  <TicketCard key={order.id} order={order} />
                ))}
                {columns[stage].length === 0 && (
                  <div className="rounded-2xl border border-dashed border-app p-6 text-center text-xs text-muted">
                    Nothing here
                  </div>
                )}
              </div>
            </div>
          ))}

          <div className="space-y-3">
            <div className="flex items-center justify-between px-1">
              <h3 className="text-sm font-bold text-app">Completed Today</h3>
              <span className="rounded-full bg-app px-2 py-0.5 text-xs font-bold text-muted">{completedToday.length}</span>
            </div>
            <div className="max-h-[70vh] space-y-2 overflow-y-auto">
              {completedToday.map((order) => (
                <div key={order.id} className="rounded-xl border border-app bg-app/40 px-3 py-2.5 text-sm">
                  <div className="flex items-center justify-between">
                    <span className="font-semibold text-app">{order.orderNumber}</span>
                    <CheckCircle2 className="h-3.5 w-3.5 text-success" />
                  </div>
                  <div className="mt-0.5 flex items-center justify-between text-[11px] text-muted">
                    <span>{order.tableName ?? order.orderType.replaceAll('_', ' ')}</span>
                    <span>{elapsedMinutes(order.updatedAt)}m ago</span>
                  </div>
                </div>
              ))}
              {completedToday.length === 0 && (
                <div className="rounded-2xl border border-dashed border-app p-6 text-center text-xs text-muted">
                  Nothing completed yet today
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
