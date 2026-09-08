import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertTriangle,
  Archive,
  ArchiveRestore,
  Check,
  History,
  MinusCircle,
  Package,
  PackagePlus,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Trash2,
  Truck,
  Warehouse,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import type { ComponentType } from 'react'

import { BranchSwitcher } from '@/components/common/BranchSwitcher'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { FeatureLockedScreen } from '@/components/ui/FeatureLockedScreen'
import { Modal } from '@/components/ui/Modal'
import { Sheet } from '@/components/ui/Sheet'
import { FullPageSpinner, Spinner } from '@/components/ui/Spinner'
import { useEntitlements } from '@/hooks/useEntitlements'
import { api } from '@/lib/api'
import { cn, formatCurrency, formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import { ApiError } from '@/types/api'
import type {
  AccessibleBranchDto,
  CreateInventoryItemRequest,
  InventoryItemDto,
  InventoryTransactionDto,
  RecordInventoryTransactionRequest,
  SetPreferredSupplierRequest,
  SupplierDto,
  UpdateInventoryItemRequest,
} from '@/types/api'

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

type TxnType = RecordInventoryTransactionRequest['type']

const TXN_META: Record<
  TxnType,
  { label: string; icon: ComponentType<{ className?: string }>; tone: 'success' | 'info' | 'warning' | 'danger'; helper: string }
> = {
  RECEIVE: { label: 'Receive', icon: PackagePlus, tone: 'success', helper: 'Stock coming in, e.g. from a delivery' },
  ADJUST: { label: 'Adjust', icon: SlidersHorizontal, tone: 'info', helper: 'Correct a count - enter the +/- change' },
  DEDUCT: { label: 'Deduct', icon: MinusCircle, tone: 'warning', helper: 'Stock consumed outside a normal sale' },
  WASTE: { label: 'Waste', icon: Trash2, tone: 'danger', helper: 'Spoilage, breakage or expiry' },
}

/** Every mutation below carries a `version`/`itemVersion` for optimistic locking - a 409 means
 * someone else changed the same record first, so surface a friendly nudge instead of a raw
 * "Conflict" message and let the caller re-open from freshly invalidated data. */
function friendlyError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 409) return 'This record was changed elsewhere. Refresh and try again.'
    return err.message || fallback
  }
  return fallback
}

function formatQty(value: number, unit?: string | null): string {
  const formatted = value.toLocaleString(undefined, { maximumFractionDigits: 3 })
  return unit ? `${formatted} ${unit}` : formatted
}

// ---------------------------------------------------------------------------
// Add / Edit item modals
// ---------------------------------------------------------------------------

function AddItemModal({ open, onClose, branches }: { open: boolean; onClose: () => void; branches: AccessibleBranchDto[] }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [unit, setUnit] = useState('')
  const [openingQuantity, setOpeningQuantity] = useState('0')
  const [reorderThreshold, setReorderThreshold] = useState('')
  const [costPerUnit, setCostPerUnit] = useState('')
  const [branchId, setBranchId] = useState('')
  const [error, setError] = useState<string | null>(null)

  // Bistrodesk branch-isolation release (requirement #3): an inventory item now always belongs to
  // exactly one branch, resolved server-side (a single-branch caller or one with a default branch
  // never needs to choose) - same "only show a picker when there's a real choice" convention as
  // CustomersPage's CustomerModal.
  const showBranchPicker = branches.length > 1

  const reset = () => {
    setName('')
    setUnit('')
    setOpeningQuantity('0')
    setReorderThreshold('')
    setCostPerUnit('')
    setBranchId('')
    setError(null)
  }

  const createItem = useMutation({
    mutationFn: () =>
      api.post<InventoryItemDto>('/inventory/items', {
        name: name.trim(),
        unit: unit.trim(),
        openingQuantity: Number(openingQuantity) || 0,
        reorderThreshold: reorderThreshold.trim() === '' ? null : Number(reorderThreshold),
        costPerUnit: costPerUnit.trim() === '' ? null : Number(costPerUnit),
        branchId: branchId || null,
      } satisfies CreateInventoryItemRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['inventory', 'items'] })
      reset()
      onClose()
    },
    onError: (err) => setError(friendlyError(err, 'Could not create this item')),
  })

  return (
    <Modal
      open={open}
      onClose={() => {
        reset()
        onClose()
      }}
      title="Add Stock Item"
      widthClassName="max-w-md"
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          createItem.mutate()
        }}
      >
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Name</label>
          <input
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Tomatoes"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        {showBranchPicker && (
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Branch</label>
            <select
              required
              value={branchId}
              onChange={(e) => setBranchId(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            >
              <option value="">Select branch…</option>
              {branches.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Unit</label>
            <input
              required
              value={unit}
              onChange={(e) => setUnit(e.target.value)}
              placeholder="e.g. kg, L, pcs"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Opening quantity</label>
            <input
              type="number"
              step="any"
              min={0}
              required
              value={openingQuantity}
              onChange={(e) => setOpeningQuantity(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Reorder threshold</label>
            <input
              type="number"
              step="any"
              min={0}
              value={reorderThreshold}
              onChange={(e) => setReorderThreshold(e.target.value)}
              placeholder="Optional"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Cost per unit</label>
            <input
              type="number"
              step="any"
              min={0}
              value={costPerUnit}
              onChange={(e) => setCostPerUnit(e.target.value)}
              placeholder="Optional"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <Button type="submit" className="w-full" disabled={createItem.isPending}>
          <Plus className="h-4 w-4" /> {createItem.isPending ? 'Adding…' : 'Add item'}
        </Button>
      </form>
    </Modal>
  )
}

function EditItemModal({ item, onClose }: { item: InventoryItemDto; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(item.name)
  const [unit, setUnit] = useState(item.unit)
  const [reorderThreshold, setReorderThreshold] = useState(item.reorderThreshold?.toString() ?? '')
  const [costPerUnit, setCostPerUnit] = useState(item.costPerUnit?.toString() ?? '')
  const [error, setError] = useState<string | null>(null)

  const updateItem = useMutation({
    mutationFn: () =>
      api.patch<InventoryItemDto>(`/inventory/items/${item.id}`, {
        name: name.trim(),
        unit: unit.trim(),
        reorderThreshold: reorderThreshold.trim() === '' ? null : Number(reorderThreshold),
        costPerUnit: costPerUnit.trim() === '' ? null : Number(costPerUnit),
        version: item.version,
      } satisfies UpdateInventoryItemRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['inventory', 'items'] })
      onClose()
    },
    onError: (err) => setError(friendlyError(err, 'Could not update this item')),
  })

  return (
    <Modal open onClose={onClose} title={`Edit ${item.name}`} widthClassName="max-w-md">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          updateItem.mutate()
        }}
      >
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Name</label>
          <input
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Unit</label>
            <input
              required
              value={unit}
              onChange={(e) => setUnit(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Reorder threshold</label>
            <input
              type="number"
              step="any"
              min={0}
              value={reorderThreshold}
              onChange={(e) => setReorderThreshold(e.target.value)}
              placeholder="Optional"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Cost per unit</label>
          <input
            type="number"
            step="any"
            min={0}
            value={costPerUnit}
            onChange={(e) => setCostPerUnit(e.target.value)}
            placeholder="Optional"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <p className="text-xs text-muted">
          Quantity on hand can only change through a Receive / Adjust / Deduct / Waste transaction, so it isn't
          editable here.
        </p>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <Button type="submit" className="w-full" disabled={updateItem.isPending}>
          {updateItem.isPending ? 'Saving…' : 'Save changes'}
        </Button>
      </form>
    </Modal>
  )
}

// ---------------------------------------------------------------------------
// Record transaction modal
// ---------------------------------------------------------------------------

function TransactionModal({
  item,
  initialType,
  onClose,
}: {
  item: InventoryItemDto
  initialType: TxnType
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [type, setType] = useState<TxnType>(initialType)
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const record = useMutation({
    mutationFn: () =>
      api.post<InventoryTransactionDto>(`/inventory/items/${item.id}/transactions`, {
        type,
        quantity: Number(quantity),
        reason: reason.trim(),
        itemVersion: item.version,
      } satisfies RecordInventoryTransactionRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['inventory', 'items'] })
      queryClient.invalidateQueries({ queryKey: ['inventory', 'items', item.id, 'transactions'] })
      onClose()
    },
    onError: (err) => setError(friendlyError(err, 'Could not record this transaction')),
  })

  const meta = TXN_META[type]
  const numericQuantity = Number(quantity)
  const quantityValid = quantity.trim() !== '' && !Number.isNaN(numericQuantity) && (type === 'ADJUST' ? numericQuantity !== 0 : numericQuantity > 0)
  const canSubmit = quantityValid && reason.trim().length > 0

  return (
    <Modal open onClose={onClose} title={`Record transaction · ${item.name}`} widthClassName="max-w-md">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          if (!canSubmit) return
          record.mutate()
        }}
      >
        <div className="text-xs text-muted">
          Current stock: <span className="font-semibold text-app">{formatQty(item.quantityOnHand, item.unit)}</span>
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Transaction type</label>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {(Object.keys(TXN_META) as TxnType[]).map((t) => {
              const m = TXN_META[t]
              const Icon = m.icon
              const active = type === t
              return (
                <button
                  key={t}
                  type="button"
                  onClick={() => setType(t)}
                  className={cn(
                    'flex flex-col items-center gap-1 rounded-xl border px-2 py-2.5 text-xs font-bold transition-colors',
                    active ? 'border-brand-500 bg-brand-600 text-white' : 'border-app text-app hover:bg-app',
                  )}
                >
                  <Icon className="h-4 w-4" />
                  {m.label}
                </button>
              )
            })}
          </div>
          <p className="mt-1.5 text-xs text-muted">{meta.helper}</p>
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">
            Quantity {type === 'ADJUST' ? '(+ to increase, - to decrease)' : `(${item.unit})`}
          </label>
          <input
            type="number"
            step="any"
            required
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            placeholder={type === 'ADJUST' ? 'e.g. -2.5' : '0.00'}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Reason (required)</label>
          <textarea
            required
            rows={2}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. Delivery from ABC Suppliers, invoice #123"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>

        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

        <Button type="submit" className="w-full" disabled={!canSubmit || record.isPending}>
          {record.isPending ? 'Recording…' : `Record ${meta.label.toLowerCase()}`}
        </Button>
      </form>
    </Modal>
  )
}

// ---------------------------------------------------------------------------
// Item detail sheet - preferred supplier + transaction ledger
// ---------------------------------------------------------------------------

const TXN_BADGE_TONE: Record<string, 'success' | 'info' | 'warning' | 'danger' | 'neutral'> = {
  RECEIVE: 'success',
  ADJUST: 'info',
  DEDUCT: 'warning',
  WASTE: 'danger',
}

function ItemDetailSheet({
  item,
  suppliers,
  canManage,
  onClose,
}: {
  item: InventoryItemDto
  suppliers: SupplierDto[]
  canManage: boolean
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [supplierId, setSupplierId] = useState(item.preferredSupplierId ?? '')
  const [supplierError, setSupplierError] = useState<string | null>(null)

  const transactionsQuery = useQuery({
    queryKey: ['inventory', 'items', item.id, 'transactions'],
    queryFn: () => api.get<InventoryTransactionDto[]>(`/inventory/items/${item.id}/transactions`),
  })

  const setSupplier = useMutation({
    mutationFn: () =>
      api.patch<InventoryItemDto>(`/inventory/items/${item.id}/preferred-supplier`, {
        supplierId: supplierId || null,
        version: item.version,
      } satisfies SetPreferredSupplierRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['inventory', 'items'] })
      setSupplierError(null)
    },
    onError: (err) => setSupplierError(friendlyError(err, 'Could not update the preferred supplier')),
  })

  const transactions = transactionsQuery.data ?? []
  const supplierChanged = (supplierId || null) !== item.preferredSupplierId

  return (
    <Sheet open onClose={onClose} title={item.name} subtitle={`${item.unit} · ${formatQty(item.quantityOnHand, item.unit)} on hand`} widthClassName="max-w-lg">
      <div className="space-y-6">
        <div className="grid grid-cols-3 gap-2 rounded-xl bg-app/50 p-3 text-xs">
          <div>
            <div className="font-semibold uppercase tracking-wide text-muted">On hand</div>
            <div className="mt-0.5 text-sm font-bold text-app">{formatQty(item.quantityOnHand, item.unit)}</div>
          </div>
          <div>
            <div className="font-semibold uppercase tracking-wide text-muted">Reorder at</div>
            <div className="mt-0.5 text-sm font-bold text-app">{item.reorderThreshold != null ? formatQty(item.reorderThreshold, item.unit) : '—'}</div>
          </div>
          <div>
            <div className="font-semibold uppercase tracking-wide text-muted">Cost / unit</div>
            <div className="mt-0.5 text-sm font-bold text-app">{item.costPerUnit != null ? formatCurrency(item.costPerUnit) : '—'}</div>
          </div>
        </div>

        {item.lowStock && (
          <div className="flex items-center gap-2 rounded-lg bg-warning-soft px-3 py-2 text-xs font-semibold text-warning">
            <AlertTriangle className="h-4 w-4 shrink-0" /> Stock is at or below the reorder threshold.
          </div>
        )}

        <div>
          <div className="mb-2 flex items-center gap-1.5 text-xs font-bold uppercase tracking-wide text-muted">
            <Truck className="h-3.5 w-3.5" /> Preferred supplier
          </div>
          <div className="flex items-center gap-2">
            <select
              disabled={!canManage}
              value={supplierId}
              onChange={(e) => setSupplierId(e.target.value)}
              className="flex-1 rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none disabled:opacity-60"
            >
              <option value="">None</option>
              {suppliers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
            {canManage && (
              <Button size="sm" variant="secondary" disabled={!supplierChanged || setSupplier.isPending} onClick={() => setSupplier.mutate()}>
                <Check className="h-3.5 w-3.5" /> Save
              </Button>
            )}
          </div>
          {supplierError && <div className="mt-2 rounded-lg bg-danger-soft px-3 py-2 text-xs text-danger">{supplierError}</div>}
        </div>

        <div>
          <div className="mb-2 flex items-center gap-1.5 text-xs font-bold uppercase tracking-wide text-muted">
            <History className="h-3.5 w-3.5" /> Transaction history
          </div>
          {transactionsQuery.isLoading ? (
            <div className="flex justify-center py-6">
              <Spinner />
            </div>
          ) : transactions.length === 0 ? (
            <p className="text-sm text-muted">No transactions recorded for this item yet.</p>
          ) : (
            <ul className="space-y-2">
              {transactions.map((txn) => (
                <li key={txn.id} className="rounded-xl border border-app p-3">
                  <div className="flex items-center justify-between gap-2">
                    <Badge tone={TXN_BADGE_TONE[txn.type] ?? 'neutral'}>{txn.type}</Badge>
                    <span className="text-xs text-muted">{formatDateTime(txn.createdAt)}</span>
                  </div>
                  <div className="mt-1.5 flex items-baseline justify-between">
                    <span className={cn('text-sm font-bold', txn.quantity < 0 ? 'text-danger' : 'text-success')}>
                      {txn.quantity > 0 ? '+' : ''}
                      {formatQty(txn.quantity, item.unit)}
                    </span>
                    <span className="text-xs text-muted">→ {formatQty(txn.resultingQuantity, item.unit)} on hand</span>
                  </div>
                  {txn.reason && <div className="mt-1 text-xs text-app">{txn.reason}</div>}
                  {txn.recordedByName && <div className="mt-0.5 text-[11px] text-muted">by {txn.recordedByName}</div>}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </Sheet>
  )
}

// ---------------------------------------------------------------------------
// Deactivate / reactivate confirm modal
// ---------------------------------------------------------------------------

function ToggleActiveModal({ item, onClose }: { item: InventoryItemDto; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const activating = !item.active

  const toggle = useMutation({
    mutationFn: () =>
      api.patch<InventoryItemDto>(`/inventory/items/${item.id}`, {
        active: activating,
        version: item.version,
      } satisfies UpdateInventoryItemRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['inventory', 'items'] })
      onClose()
    },
    onError: (err) => setError(friendlyError(err, 'Could not update this item')),
  })

  return (
    <Modal open onClose={onClose} title={`${activating ? 'Reactivate' : 'Deactivate'} ${item.name}?`} widthClassName="max-w-sm">
      <div className="space-y-4">
        <p className="text-sm text-muted">
          {activating
            ? 'This item will reappear in active stock lists and can receive transactions again.'
            : 'This item is soft-deleted, not removed - it stays in history but is hidden from the active list and can no longer receive transactions.'}
        </p>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button variant={activating ? 'primary' : 'danger'} className="flex-1" disabled={toggle.isPending} onClick={() => toggle.mutate()}>
            {activating ? <ArchiveRestore className="h-4 w-4" /> : <Archive className="h-4 w-4" />}
            {toggle.isPending ? 'Saving…' : activating ? 'Reactivate' : 'Deactivate'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// ---------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------

export function InventoryPage() {
  const { hasPermission, terminal, defaultBranchId } = useAuthStore()
  const { isEnabled: isFeatureEnabled, isLoading: entitlementsLoading } = useEntitlements()
  const queryClient = useQueryClient()

  const canManage = hasPermission('INVENTORY_MANAGE')
  const canAdjust = canManage || hasPermission('INVENTORY_ADJUST')

  const [search, setSearch] = useState('')
  const [lowStockOnly, setLowStockOnly] = useState(false)
  const [showInactive, setShowInactive] = useState(false)

  const [addOpen, setAddOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<InventoryItemDto | null>(null)
  const [txnTarget, setTxnTarget] = useState<{ item: InventoryItemDto; type: TxnType } | null>(null)
  const [detailTargetId, setDetailTargetId] = useState<string | null>(null)
  const [toggleTarget, setToggleTarget] = useState<InventoryItemDto | null>(null)

  // Bistrodesk branch-isolation release (requirement #3): this previously never passed a branchId
  // at all, so any unrestricted account (Owner/Admin with no branch assignments, or
  // VIEW_ALL_BRANCHES) always saw every branch's stock merged together, regardless of terminal -
  // confirmed real-world bug ("inventory added at branch A is visible/editable from branch B").
  // Defaults to this terminal's own bound branch, same convention as Customers/Orders Log, with
  // the same Branch Switcher for a legitimate cross-branch look (e.g. an Owner auditing more than
  // one location).
  const [branchId, setBranchId] = useState<string | null>(terminal?.branchId ?? defaultBranchId ?? null)

  // Bistrodesk branch-isolation release (requirement #5): InventoryController is class-level
  // gated on INVENTORY_MANAGEMENT server-side - only fetched once entitlements have loaded and
  // confirm it's actually enabled, so an excluded branch never fires a request guaranteed to 403.
  const itemsQuery = useQuery({
    queryKey: ['inventory', 'items', branchId],
    queryFn: () => api.get<InventoryItemDto[]>('/inventory/items', { branchId: branchId ?? undefined }),
    refetchInterval: 30_000,
    enabled: !entitlementsLoading && isFeatureEnabled('INVENTORY_MANAGEMENT'),
  })

  // Bistrodesk branch-isolation release (requirement #7): Suppliers itself moved to its own view
  // under Purchase Orders, but this item detail sheet's preferred-supplier picker (and the
  // "Suppliers" stat card below) still needs the supplier list.
  const suppliersQuery = useQuery({
    queryKey: ['suppliers'],
    queryFn: () => api.get<SupplierDto[]>('/suppliers'),
  })

  // Bistrodesk branch-isolation release (requirement #3): only fetched to decide whether the Add
  // Item form's branch picker should render at all - a single-branch/already-scoped caller never
  // sees more than one entry here, so the picker stays hidden and the server-side default
  // resolution (this caller's own branch) is all that's ever needed.
  const branchesQuery = useQuery({
    queryKey: ['branches', 'accessible'],
    queryFn: () => api.get<AccessibleBranchDto[]>('/branches/accessible'),
  })

  const items = itemsQuery.data ?? []
  const suppliers = suppliersQuery.data ?? []
  const branches = branchesQuery.data ?? []

  const filtered = useMemo(() => {
    let list = items
    if (!showInactive) list = list.filter((i) => i.active)
    if (lowStockOnly) list = list.filter((i) => i.lowStock)
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      list = list.filter((i) => i.name.toLowerCase().includes(q) || i.unit.toLowerCase().includes(q))
    }
    return [...list].sort((a, b) => {
      if (a.lowStock !== b.lowStock) return a.lowStock ? -1 : 1
      return a.name.localeCompare(b.name)
    })
  }, [items, showInactive, lowStockOnly, search])

  const stats = useMemo(() => {
    const active = items.filter((i) => i.active)
    const lowStock = active.filter((i) => i.lowStock).length
    const totalValue = active.reduce((sum, i) => sum + (i.costPerUnit ?? 0) * i.quantityOnHand, 0)
    return { total: active.length, lowStock, totalValue }
  }, [items])

  const detailItem = detailTargetId ? (items.find((i) => i.id === detailTargetId) ?? null) : null

  // Bistrodesk branch-isolation release (requirement #7): Suppliers moved to its own view under
  // Purchase Orders (see PurchaseOrdersPage.tsx) - this page's only view now is Stock Items, so
  // the INVENTORY_MANAGEMENT lock can simply replace the whole page body again.
  const inventoryFeatureEnabled = isFeatureEnabled('INVENTORY_MANAGEMENT')

  if (itemsQuery.isLoading || entitlementsLoading) return <FullPageSpinner label="Loading inventory…" />

  return (
    <div className="space-y-5">
      <div>
        <h2 className="flex items-center gap-2 text-lg font-bold text-app">
          <Warehouse className="h-5 w-5 text-brand-600" /> Inventory
        </h2>
        <p className="mt-0.5 text-sm text-muted">Track raw ingredients and supplies, and record stock movements.</p>
      </div>

      {!inventoryFeatureEnabled ? (
        <FeatureLockedScreen
          title="Not Included in Your Plan"
          message="Inventory Management isn't part of this branch's current subscription plan. Contact Bistrodesk support to upgrade."
        />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Card className="p-3.5">
              <div className="text-[11px] font-semibold uppercase tracking-wide text-muted">Active items</div>
              <div className="mt-1 text-xl font-extrabold text-app">{stats.total}</div>
            </Card>
            <Card className={cn('p-3.5', stats.lowStock > 0 && 'border-warning/40 bg-warning-soft/40')}>
              <div className="text-[11px] font-semibold uppercase tracking-wide text-muted">Low stock</div>
              <div className={cn('mt-1 text-xl font-extrabold', stats.lowStock > 0 ? 'text-warning' : 'text-app')}>{stats.lowStock}</div>
            </Card>
            <Card className="p-3.5">
              <div className="text-[11px] font-semibold uppercase tracking-wide text-muted">Stock value</div>
              <div className="mt-1 text-xl font-extrabold text-app">{formatCurrency(stats.totalValue)}</div>
            </Card>
            <Card className="p-3.5">
              <div className="text-[11px] font-semibold uppercase tracking-wide text-muted">Suppliers</div>
              <div className="mt-1 text-xl font-extrabold text-app">{suppliers.length}</div>
            </Card>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <div className="relative max-w-xs flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search items…"
                className="w-full rounded-lg border border-app bg-surface py-2 pl-9 pr-3 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
              />
            </div>
            <button
              type="button"
              onClick={() => setLowStockOnly((v) => !v)}
              className={cn(
                'flex items-center gap-1.5 rounded-lg border px-3 py-2 text-xs font-semibold transition-colors',
                lowStockOnly ? 'border-warning/50 bg-warning-soft text-warning' : 'border-app bg-surface text-app hover:bg-app',
              )}
            >
              <AlertTriangle className="h-3.5 w-3.5" /> Low stock only
            </button>
            <button
              type="button"
              onClick={() => setShowInactive((v) => !v)}
              className={cn(
                'flex items-center gap-1.5 rounded-lg border px-3 py-2 text-xs font-semibold transition-colors',
                showInactive ? 'border-brand-500 bg-brand-100 text-brand-700 dark:bg-brand-900 dark:text-brand-200' : 'border-app bg-surface text-app hover:bg-app',
              )}
            >
              <Archive className="h-3.5 w-3.5" /> Show inactive
            </button>
            <BranchSwitcher value={branchId} onChange={setBranchId} />
            <Button size="sm" variant="secondary" onClick={() => itemsQuery.refetch()}>
              <RefreshCw className={cn('h-3.5 w-3.5', itemsQuery.isFetching && 'animate-spin')} /> Refresh
            </Button>
            {canManage && (
              <Button size="sm" onClick={() => setAddOpen(true)}>
                <Plus className="h-3.5 w-3.5" /> Add Item
              </Button>
            )}
          </div>

          {itemsQuery.isError && (
            <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
              {friendlyError(itemsQuery.error, 'Could not load inventory items')}
            </div>
          )}

          {filtered.length === 0 ? (
            <div className="flex h-64 flex-col items-center justify-center gap-2 text-muted">
              <Package className="h-8 w-8" />
              <span className="text-sm font-medium">No stock items match these filters</span>
            </div>
          ) : (
            <Card className="overflow-hidden">
              <div className="overflow-x-auto">
                <table className="w-full min-w-[860px] text-left text-sm">
                  <thead>
                    <tr className="border-b border-app bg-app/40 text-[11px] font-bold uppercase tracking-wide text-muted">
                      <th className="px-4 py-2.5">Item</th>
                      <th className="px-4 py-2.5">On hand</th>
                      <th className="px-4 py-2.5">Reorder at</th>
                      <th className="px-4 py-2.5">Cost / unit</th>
                      <th className="px-4 py-2.5">Preferred supplier</th>
                      <th className="px-4 py-2.5">Status</th>
                      <th className="px-4 py-2.5 text-right">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filtered.map((item) => (
                      <tr
                        key={item.id}
                        className={cn(
                          'border-b border-app last:border-0',
                          item.lowStock ? 'bg-danger-soft/20' : 'hover:bg-app/40',
                          !item.active && 'opacity-60',
                        )}
                      >
                        <td className="px-4 py-3">
                          <button type="button" className="text-left font-semibold text-app hover:text-brand-600" onClick={() => setDetailTargetId(item.id)}>
                            {item.name}
                          </button>
                          <div className="text-xs text-muted">{item.unit}</div>
                        </td>
                        <td className="px-4 py-3 font-semibold text-app">{formatQty(item.quantityOnHand)}</td>
                        <td className="px-4 py-3 text-muted">{item.reorderThreshold != null ? formatQty(item.reorderThreshold) : '—'}</td>
                        <td className="px-4 py-3 text-muted">{item.costPerUnit != null ? formatCurrency(item.costPerUnit) : '—'}</td>
                        <td className="px-4 py-3 text-muted">{item.preferredSupplierName ?? '—'}</td>
                        <td className="px-4 py-3">
                          {!item.active ? (
                            <Badge tone="neutral">Inactive</Badge>
                          ) : item.lowStock ? (
                            <Badge tone="warning">Low stock</Badge>
                          ) : (
                            <Badge tone="success">OK</Badge>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center justify-end gap-1">
                            {canAdjust && item.active && (
                              <>
                                <button
                                  type="button"
                                  title="Receive stock"
                                  className="rounded-lg p-1.5 text-muted hover:bg-success-soft hover:text-success"
                                  onClick={() => setTxnTarget({ item, type: 'RECEIVE' })}
                                >
                                  <PackagePlus className="h-4 w-4" />
                                </button>
                                <button
                                  type="button"
                                  title="Adjust stock"
                                  className="rounded-lg p-1.5 text-muted hover:bg-info-soft hover:text-info"
                                  onClick={() => setTxnTarget({ item, type: 'ADJUST' })}
                                >
                                  <SlidersHorizontal className="h-4 w-4" />
                                </button>
                                <button
                                  type="button"
                                  title="Record waste"
                                  className="rounded-lg p-1.5 text-muted hover:bg-danger-soft hover:text-danger"
                                  onClick={() => setTxnTarget({ item, type: 'WASTE' })}
                                >
                                  <Trash2 className="h-4 w-4" />
                                </button>
                              </>
                            )}
                            <button
                              type="button"
                              title="View history"
                              className="rounded-lg p-1.5 text-muted hover:bg-app"
                              onClick={() => setDetailTargetId(item.id)}
                            >
                              <History className="h-4 w-4" />
                            </button>
                            {canManage && (
                              <>
                                <button
                                  type="button"
                                  title="Edit item"
                                  className="rounded-lg p-1.5 text-muted hover:bg-app"
                                  onClick={() => setEditTarget(item)}
                                >
                                  <Pencil className="h-4 w-4" />
                                </button>
                                <button
                                  type="button"
                                  title={item.active ? 'Deactivate item' : 'Reactivate item'}
                                  className="rounded-lg p-1.5 text-muted hover:bg-app"
                                  onClick={() => setToggleTarget(item)}
                                >
                                  {item.active ? <Archive className="h-4 w-4" /> : <ArchiveRestore className="h-4 w-4" />}
                                </button>
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          )}

          {!canManage && !canAdjust && (
            <p className="text-xs text-muted">
              You have read-only access to inventory - ask an admin for the Inventory Manage or Inventory Adjust
              permission to record transactions.
            </p>
          )}
        </>
      )}

      {addOpen && <AddItemModal open={addOpen} onClose={() => setAddOpen(false)} branches={branches} />}
      {editTarget && <EditItemModal item={editTarget} onClose={() => setEditTarget(null)} />}
      {txnTarget && <TransactionModal item={txnTarget.item} initialType={txnTarget.type} onClose={() => setTxnTarget(null)} />}
      {toggleTarget && <ToggleActiveModal item={toggleTarget} onClose={() => setToggleTarget(null)} />}
      {detailItem && (
        <ItemDetailSheet
          item={detailItem}
          suppliers={suppliers}
          canManage={canManage}
          onClose={() => {
            setDetailTargetId(null)
            queryClient.invalidateQueries({ queryKey: ['inventory', 'items'] })
          }}
        />
      )}
    </div>
  )
}
