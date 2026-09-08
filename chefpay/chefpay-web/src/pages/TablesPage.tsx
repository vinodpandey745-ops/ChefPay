import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowRightLeft,
  Check,
  ChevronRight,
  LayoutGrid,
  Pencil,
  Plus,
  RefreshCw,
  Receipt,
  Trash2,
  Users2,
  X,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Modal } from '@/components/ui/Modal'
import { Sheet } from '@/components/ui/Sheet'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useLiveTopics } from '@/hooks/useLiveTopics'
import { api } from '@/lib/api'
import { useAuthStore } from '@/store/auth'
import { cn, formatCurrency } from '@/lib/utils'
import { ApiError } from '@/types/api'
import type { OrderDto, TableDto, TableFloorDto } from '@/types/api'

const STATUS_OPTIONS = [
  'AVAILABLE',
  'RESERVED',
  'OCCUPIED',
  'ORDER_PLACED',
  'PREPARING',
  'READY',
  'BILL_REQUESTED',
  'PAYMENT_PENDING',
  'CLOSED',
  'BLOCKED',
]

/** Every non-AVAILABLE/RESERVED status is some flavor of "occupied, order in flight" - the legend
 * bar and card coloring both collapse to these four buckets, mirroring the reference POS's
 * Available/Occupied/Reserved/Cleaning grouping instead of exposing all ten raw backend statuses as
 * separate visual buckets (which is exactly the "extremely poor" table view directive #2 called out). */
type Bucket = 'AVAILABLE' | 'OCCUPIED' | 'RESERVED' | 'CLEANING'

function bucketOf(status: string): Bucket {
  if (status === 'AVAILABLE') return 'AVAILABLE'
  if (status === 'RESERVED') return 'RESERVED'
  if (status === 'CLOSED' || status === 'BLOCKED') return 'CLEANING'
  return 'OCCUPIED'
}

const BUCKET_STYLE: Record<Bucket, { card: string; dot: string; label: string }> = {
  AVAILABLE: { card: 'border-success/40 bg-success-soft/60 hover:bg-success-soft', dot: 'bg-success', label: 'Available' },
  OCCUPIED: { card: 'border-warning/40 bg-warning-soft/60 hover:bg-warning-soft', dot: 'bg-warning', label: 'Occupied' },
  RESERVED: { card: 'border-info/40 bg-info-soft/60 hover:bg-info-soft', dot: 'bg-info', label: 'Reserved' },
  CLEANING: { card: 'border-app bg-app/70 hover:bg-app', dot: 'bg-muted', label: 'Cleaning / Blocked' },
}

const PAYMENT_TONE: Record<string, 'success' | 'warning' | 'neutral'> = {
  PAID: 'success',
  PENDING: 'warning',
  PARTIAL: 'warning',
}

function TableCard({ table, order, onOpen }: { table: TableDto; order: OrderDto | undefined; onOpen: () => void }) {
  const bucket = bucketOf(table.status)
  const style = BUCKET_STYLE[bucket]

  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn('flex flex-col rounded-2xl border p-3.5 text-left transition-colors', style.card)}
    >
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="text-base font-bold text-app">{table.name}</div>
          {table.section && <div className="text-xs text-muted">{table.section}</div>}
        </div>
        <div className="flex items-center gap-1 text-xs font-medium text-muted">
          <Users2 className="h-3.5 w-3.5" />
          {table.seatingCapacity}
        </div>
      </div>

      <div className="mt-2 flex items-center gap-1.5 text-xs font-semibold">
        <span className={cn('h-1.5 w-1.5 rounded-full', style.dot)} />
        <span className="text-app">{table.status.replaceAll('_', ' ')}</span>
      </div>

      {order && (
        <div className="mt-3 space-y-1.5 border-t border-black/5 pt-2.5 dark:border-white/10">
          <div className="flex items-center justify-between">
            <span className="text-[11px] font-semibold text-muted">{order.orderNumber}</span>
            <Badge tone={PAYMENT_TONE[order.paymentStatus] ?? 'neutral'}>{order.paymentStatus}</Badge>
          </div>
          <div className="text-lg font-extrabold text-app">{formatCurrency(order.totalAmount)}</div>
        </div>
      )}
    </button>
  )
}

function AddTableModal({ open, onClose, defaultFloorId }: { open: boolean; onClose: () => void; defaultFloorId: string | undefined }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [seatingCapacity, setSeatingCapacity] = useState(4)
  const [section, setSection] = useState('')
  const [error, setError] = useState<string | null>(null)

  const createTable = useMutation({
    mutationFn: () =>
      api.post<TableDto>('/tables', {
        floorId: defaultFloorId,
        name,
        seatingCapacity,
        section: section || null,
        gridRow: 0,
        gridColumn: 0,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tables'] })
      setName('')
      setSection('')
      setSeatingCapacity(4)
      onClose()
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not create table'),
  })

  return (
    <Modal open={open} onClose={onClose} title="Add table" widthClassName="max-w-sm">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          createTable.mutate()
        }}
      >
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Table name</label>
          <input
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. T9"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Seating capacity</label>
          <input
            type="number"
            min={1}
            required
            value={seatingCapacity}
            onChange={(e) => setSeatingCapacity(Number(e.target.value))}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Section (optional)</label>
          <input
            value={section}
            onChange={(e) => setSection(e.target.value)}
            placeholder="e.g. Patio"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <Button type="submit" className="w-full" disabled={createTable.isPending || !defaultFloorId}>
          <Plus className="h-4 w-4" /> {createTable.isPending ? 'Adding…' : 'Add table'}
        </Button>
      </form>
    </Modal>
  )
}

/** Bistrodesk follow-up requirement #5 ("every table... should have option to edit/update") - a
 * table's name/seating capacity/section were only ever settable at creation before this; the only
 * post-creation control this screen had was the status buttons in QuickActionsPanel below. */
function EditTableModal({ table, onClose }: { table: TableDto; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(table.name)
  const [seatingCapacity, setSeatingCapacity] = useState(table.seatingCapacity)
  const [section, setSection] = useState(table.section ?? '')
  const [error, setError] = useState<string | null>(null)

  const save = useMutation({
    mutationFn: () =>
      api.patch<TableDto>(`/tables/${table.id}`, {
        name: name.trim() || null,
        seatingCapacity,
        section: section.trim() || null,
        version: table.version,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tables'] })
      onClose()
    },
    onError: (err) =>
      setError(
        err instanceof ApiError && err.errorCode === 'VERSION_CONFLICT'
          ? 'This table was changed elsewhere - refresh and try again.'
          : err instanceof ApiError
            ? err.message
            : 'Could not update this table',
      ),
  })

  return (
    <Modal open onClose={onClose} title={`Edit ${table.name}`} widthClassName="max-w-sm">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          save.mutate()
        }}
      >
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Table name</label>
          <input
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Seating capacity</label>
          <input
            type="number"
            min={1}
            required
            value={seatingCapacity}
            onChange={(e) => setSeatingCapacity(Number(e.target.value))}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Section (optional)</label>
          <input
            value={section}
            onChange={(e) => setSection(e.target.value)}
            placeholder="e.g. Patio"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button type="button" variant="secondary" className="flex-1" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" className="flex-1" disabled={save.isPending || !name.trim()}>
            {save.isPending ? 'Saving…' : 'Save changes'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

/** Bistrodesk follow-up requirement #5 ("every table... should have option to... delete") - a
 * soft-delete (server sets active=false, see TableController#delete's javadoc for why never a hard
 * delete), blocked server-side unless the table is currently AVAILABLE - surfaced here rather than
 * silently disabling the button, so a manager sees exactly why in the moment they try. */
function DeleteTableModal({ table, onClose }: { table: TableDto; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const del = useMutation({
    mutationFn: () => api.delete<void>(`/tables/${table.id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tables'] })
      onClose()
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not delete this table'),
  })

  return (
    <Modal open onClose={onClose} title={`Delete "${table.name}"?`} widthClassName="max-w-sm">
      <div className="space-y-4">
        <p className="text-sm text-muted">
          This removes the table from the table matrix. It only succeeds while the table is Available -
          release it first if it currently has an active order.
        </p>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button type="button" variant="secondary" className="flex-1" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="button"
            variant="danger"
            className="flex-1"
            disabled={del.isPending}
            onClick={() => {
              setError(null)
              del.mutate()
            }}
          >
            {del.isPending ? 'Deleting…' : 'Delete table'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

function QuickActionsPanel({
  table,
  order,
  allTables,
  onClose,
  onEdit,
  onDelete,
}: {
  table: TableDto
  order: OrderDto | undefined
  allTables: TableDto[]
  onClose: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const [moveTargetId, setMoveTargetId] = useState('')

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['tables'] })
    queryClient.invalidateQueries({ queryKey: ['orders', 'open'] })
  }

  const releaseTable = useMutation({
    mutationFn: () => api.patch<TableDto>(`/tables/${table.id}`, { status: 'AVAILABLE', version: table.version }),
    onSuccess: () => {
      invalidate()
      onClose()
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not release table'),
  })

  const setStatus = useMutation({
    mutationFn: (status: string) => api.patch<TableDto>(`/tables/${table.id}`, { status, version: table.version }),
    onSuccess: () => {
      invalidate()
      onClose()
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not update table'),
  })

  const moveTable = useMutation({
    mutationFn: () =>
      api.patch<OrderDto>(`/orders/${order!.id}/table`, { tableId: moveTargetId, orderVersion: order!.version }),
    onSuccess: () => {
      invalidate()
      onClose()
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : 'Could not move table - it may already have an active order'),
  })

  const availableTargets = allTables.filter((t) => t.id !== table.id && t.status === 'AVAILABLE')

  if (!order) {
    // Available/reserved table with nothing on it yet - a lightweight panel, not the full snapshot.
    return (
      <div className="space-y-5">
        <div className="flex items-center gap-2 text-sm text-muted">
          <Users2 className="h-4 w-4" /> Seats {table.seatingCapacity}
          {table.section && <span>· {table.section}</span>}
        </div>

        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

        <Button className="w-full" onClick={() => navigate(`/pos?tableId=${table.id}`)}>
          <Receipt className="h-4 w-4" /> Start New Order
        </Button>

        <div>
          <div className="mb-2 text-xs font-bold uppercase tracking-wide text-muted">Set status</div>
          <div className="grid grid-cols-2 gap-2">
            {STATUS_OPTIONS.filter((s) => s !== table.status).map((s) => (
              <button
                key={s}
                type="button"
                disabled={setStatus.isPending}
                onClick={() => setStatus.mutate(s)}
                className="rounded-lg border border-app px-3 py-2 text-left text-xs font-semibold text-app hover:bg-app disabled:opacity-50"
              >
                {s.replaceAll('_', ' ')}
              </button>
            ))}
          </div>
        </div>

        <div className="flex gap-2 border-t border-app pt-4">
          <Button variant="secondary" className="flex-1" onClick={onEdit}>
            <Pencil className="h-3.5 w-3.5" /> Edit
          </Button>
          <Button
            variant="danger"
            className="flex-1"
            disabled={table.status !== 'AVAILABLE'}
            onClick={onDelete}
          >
            <Trash2 className="h-3.5 w-3.5" /> Delete
          </Button>
        </div>
        {table.status !== 'AVAILABLE' && (
          <p className="text-center text-xs text-muted">Set this table back to Available before deleting it.</p>
        )}
      </div>
    )
  }

  const activeItems = order.items.filter((i) => !['CANCELLED', 'VOIDED'].includes(i.status))

  return (
    <div className="space-y-5">
      {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

      <div>
        <div className="mb-2 text-xs font-bold uppercase tracking-wide text-muted">Active Order Snapshot</div>
        <div className="rounded-xl border border-app bg-app/60 p-3.5">
          <div className="flex items-center justify-between">
            <span className="text-sm font-bold text-app">{order.orderNumber}</span>
            <Badge tone={PAYMENT_TONE[order.paymentStatus] ?? 'neutral'}>{order.paymentStatus}</Badge>
          </div>
          <div className="mt-1 text-2xl font-extrabold text-app">{formatCurrency(order.totalAmount)}</div>
          <Button className="mt-3 w-full" onClick={() => navigate(`/pos?orderId=${order.id}`)}>
            <Receipt className="h-4 w-4" /> View Order Cart / Checkout
          </Button>
        </div>
      </div>

      <div>
        <div className="mb-2 text-xs font-bold uppercase tracking-wide text-muted">Dish Preparation Status</div>
        <ul className="space-y-1.5">
          {activeItems.map((item) => (
            <li key={item.id} className="flex items-center justify-between gap-2 rounded-lg bg-app/40 px-3 py-2 text-sm">
              <span className="text-app">
                {item.quantity}× {item.menuItemName}
              </span>
              <Badge tone={item.status === 'READY' || item.status === 'SERVED' ? 'success' : 'info'}>{item.status}</Badge>
            </li>
          ))}
          {activeItems.length === 0 && <li className="text-sm text-muted">No items yet.</li>}
        </ul>
      </div>

      <div>
        <div className="mb-2 text-xs font-bold uppercase tracking-wide text-muted">Order Logistics</div>
        <div className="space-y-2 rounded-xl border border-app p-3">
          <div className="flex items-center gap-2">
            <ArrowRightLeft className="h-4 w-4 shrink-0 text-muted" />
            <select
              value={moveTargetId}
              onChange={(e) => setMoveTargetId(e.target.value)}
              className="flex-1 rounded-lg border border-app bg-app px-2 py-1.5 text-sm text-app outline-none"
            >
              <option value="">Move to table…</option>
              {availableTargets.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
            <Button size="sm" variant="secondary" disabled={!moveTargetId || moveTable.isPending} onClick={() => moveTable.mutate()}>
              <Check className="h-3.5 w-3.5" /> Move
            </Button>
          </div>
          {availableTargets.length === 0 && <p className="text-xs text-muted">No other tables are free to move to right now.</p>}
        </div>
      </div>

      <div className="space-y-2 border-t border-app pt-4">
        <Button variant="secondary" className="w-full" disabled={releaseTable.isPending} onClick={() => releaseTable.mutate()}>
          <X className="h-4 w-4" /> Release Table to Available
        </Button>
        <Button variant="ghost" className="w-full" onClick={onEdit}>
          <Pencil className="h-3.5 w-3.5" /> Edit table details
        </Button>
      </div>
    </div>
  )
}

export function TablesPage() {
  const navigate = useNavigate()
  const [selectedTableId, setSelectedTableId] = useState<string | null>(null)
  const [addOpen, setAddOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<TableDto | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<TableDto | null>(null)

  // Bistrodesk branch-isolation release (requirement #7): pass this physical terminal's own bound
  // branch explicitly rather than letting the server infer scope from the caller's overall
  // permission set - the terminal's bound branch is the stronger, unambiguous signal for "which
  // branch's tables should this screen show," matching PosTerminalPage's identical
  // `terminal?.branchId ?? defaultBranchId` convention. A caller with no terminal/default branch
  // (e.g. a back-office multi-branch admin) sends no branchId and gets the server's own
  // `resolveEffectiveBranchId`-with-fallback behavior - see TableController#list's javadoc.
  const { terminal, defaultBranchId } = useAuthStore()
  const tablesBranchId = terminal?.branchId ?? defaultBranchId ?? undefined
  const tablesQuery = useQuery({
    queryKey: ['tables', tablesBranchId],
    queryFn: () => api.get<TableDto[]>('/tables', tablesBranchId ? { branchId: tablesBranchId } : undefined),
    refetchInterval: 20_000,
  })

  // Bistrodesk post-release fix: this never passed a branchId either, so it stayed on
  // OrderController's own default (unfiltered for an unrestricted/multi-branch caller, even after
  // the tables query above was fixed) - the open-orders overlay on each table card could carry
  // another branch's orders into this terminal's memory even though the (already-scoped) tables list
  // itself only shows this branch's tables. Passes the same `tablesBranchId` for consistency.
  const ordersQuery = useQuery({
    queryKey: ['orders', 'open', tablesBranchId],
    queryFn: () => api.get<OrderDto[]>('/orders', tablesBranchId ? { branchId: tablesBranchId } : undefined),
    refetchInterval: 20_000,
  })

  // Bistrodesk follow-up requirement #5 (table creation/seeding fix): the "Add Table" floor picker
  // no longer derives its target floor from an already-existing table (tables[0]?.floorId), which
  // broke the moment a branch had zero currently-active tables - see AddTableModal's defaultFloorId
  // prop below and TableController#listFloors's own javadoc for the full story.
  const floorsQuery = useQuery({
    queryKey: ['tables', 'floors', tablesBranchId],
    queryFn: () => api.get<TableFloorDto[]>('/tables/floors', tablesBranchId ? { branchId: tablesBranchId } : undefined),
  })

  useLiveTopics(['/topic/tables', '/topic/orders'], [['tables'], ['orders', 'open', tablesBranchId]])

  const tables = tablesQuery.data ?? []
  const orders = ordersQuery.data ?? []
  const orderByTableId = useMemo(() => {
    const map = new Map<string, OrderDto>()
    for (const o of orders) if (o.tableId) map.set(o.tableId, o)
    return map
  }, [orders])

  const counts = useMemo(() => {
    const c: Record<Bucket, number> = { AVAILABLE: 0, OCCUPIED: 0, RESERVED: 0, CLEANING: 0 }
    for (const t of tables) c[bucketOf(t.status)]++
    return c
  }, [tables])

  const selectedTable = tables.find((t) => t.id === selectedTableId) ?? null
  const selectedOrder = selectedTable ? orderByTableId.get(selectedTable.id) : undefined

  if (tablesQuery.isLoading) return <FullPageSpinner label="Loading tables…" />

  const bySection = new Map<string, TableDto[]>()
  for (const t of tables) {
    const key = t.section ?? 'Main Floor'
    if (!bySection.has(key)) bySection.set(key, [])
    bySection.get(key)!.push(t)
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-app bg-surface px-4 py-3">
        <div className="flex flex-wrap items-center gap-4 text-xs font-semibold">
          {(Object.keys(BUCKET_STYLE) as Bucket[]).map((b) => (
            <div key={b} className="flex items-center gap-1.5">
              <span className={cn('h-2 w-2 rounded-full', BUCKET_STYLE[b].dot)} />
              <span className="text-app">
                {BUCKET_STYLE[b].label} ({counts[b]})
              </span>
            </div>
          ))}
        </div>
        <div className="flex items-center gap-2">
          <Button
            size="sm"
            variant="secondary"
            onClick={() => {
              tablesQuery.refetch()
              ordersQuery.refetch()
            }}
          >
            <RefreshCw className={cn('h-3.5 w-3.5', tablesQuery.isFetching && 'animate-spin')} /> Refresh
          </Button>
          <Button size="sm" onClick={() => setAddOpen(true)}>
            <Plus className="h-3.5 w-3.5" /> Add Table
          </Button>
        </div>
      </div>

      {tables.length === 0 ? (
        <div className="flex h-64 flex-col items-center justify-center gap-2 text-muted">
          <LayoutGrid className="h-8 w-8" />
          <span className="text-sm font-medium">No tables configured yet</span>
        </div>
      ) : (
        [...bySection.entries()].map(([section, sectionTables]) => (
          <div key={section}>
            <h2 className="mb-2 flex items-center gap-1 text-sm font-bold text-app">
              {section} <ChevronRight className="h-3.5 w-3.5 text-muted" /> {sectionTables.length} tables
            </h2>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6">
              {sectionTables.map((table) => (
                <TableCard
                  key={table.id}
                  table={table}
                  order={orderByTableId.get(table.id)}
                  onOpen={() => setSelectedTableId(table.id)}
                />
              ))}
            </div>
          </div>
        ))
      )}

      <Sheet
        open={!!selectedTable}
        onClose={() => setSelectedTableId(null)}
        title={selectedTable?.name ?? ''}
        subtitle={selectedOrder ? `${selectedOrder.orderType.replaceAll('_', ' ')} · ${selectedTable?.status.replaceAll('_', ' ')}` : selectedTable?.status.replaceAll('_', ' ')}
      >
        {selectedTable && (
          <QuickActionsPanel
            table={selectedTable}
            order={selectedOrder}
            allTables={tables}
            onClose={() => setSelectedTableId(null)}
            onEdit={() => setEditTarget(selectedTable)}
            onDelete={() => setDeleteTarget(selectedTable)}
          />
        )}
      </Sheet>

      <AddTableModal open={addOpen} onClose={() => setAddOpen(false)} defaultFloorId={floorsQuery.data?.[0]?.id} />
      {editTarget && <EditTableModal table={editTarget} onClose={() => setEditTarget(null)} />}
      {deleteTarget && <DeleteTableModal table={deleteTarget} onClose={() => setDeleteTarget(null)} />}

      {/* Keeps the "Start New Order" quick action reachable even with zero tables configured. */}
      {tables.length === 0 && (
        <Button variant="secondary" onClick={() => navigate('/pos')}>
          Go to POS Terminal
        </Button>
      )}
    </div>
  )
}
