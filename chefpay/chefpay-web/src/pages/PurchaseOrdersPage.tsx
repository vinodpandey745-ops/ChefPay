import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Building2,
  CheckCircle2,
  ClipboardList,
  FileText,
  Mail,
  Package,
  Pencil,
  Phone,
  Plus,
  Printer,
  RefreshCw,
  Send,
  ShieldAlert,
  ShoppingBag,
  Sparkles,
  Trash2,
  Truck,
  XCircle,
} from 'lucide-react'
import { useEffect, useState } from 'react'

import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { FeatureLockedScreen } from '@/components/ui/FeatureLockedScreen'
import { Modal } from '@/components/ui/Modal'
import { FullPageSpinner, Spinner } from '@/components/ui/Spinner'
import { useEntitlements } from '@/hooks/useEntitlements'
import { api } from '@/lib/api'
import { cn, formatCurrency, formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import { ApiError } from '@/types/api'
import type {
  AccessibleBranchDto,
  CreatePurchaseOrderItemRequest,
  CreatePurchaseOrderRequest,
  CreateSupplierRequest,
  InventoryItemDto,
  PurchaseOrderDto,
  PurchaseOrderStatus,
  ReceiveItemsRequest,
  RejectPurchaseOrderRequest,
  ReplenishmentSuggestionsResponse,
  ShareLogDto,
  ShareRequest,
  SupplierDto,
  UpdatePurchaseOrderRequest,
  UpdateSupplierRequest,
} from '@/types/api'

// ---- Presentation config - the backend only ever sends these nine statuses (PurchaseOrderStatus
// in types/api.ts / com.chefpay.core.domain.PurchaseOrderStatus). ----

const STATUS_TONE: Record<PurchaseOrderStatus, 'neutral' | 'info' | 'warning' | 'success' | 'danger' | 'brand'> = {
  DRAFT: 'neutral',
  PENDING_APPROVAL: 'warning',
  APPROVED: 'info',
  REJECTED: 'danger',
  SENT_TO_SUPPLIER: 'brand',
  PARTIALLY_RECEIVED: 'warning',
  RECEIVED: 'success',
  CLOSED: 'success',
  CANCELLED: 'danger',
}

const STATUS_LABEL: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'Draft',
  PENDING_APPROVAL: 'Pending Approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  SENT_TO_SUPPLIER: 'Sent to Supplier',
  PARTIALLY_RECEIVED: 'Partially Received',
  RECEIVED: 'Received',
  CLOSED: 'Closed',
  CANCELLED: 'Cancelled',
}

const STATUS_FILTERS: Array<{ key: 'ALL' | PurchaseOrderStatus; label: string }> = [
  { key: 'ALL', label: 'All' },
  { key: 'DRAFT', label: 'Draft' },
  { key: 'PENDING_APPROVAL', label: 'Pending Approval' },
  { key: 'APPROVED', label: 'Approved' },
  { key: 'SENT_TO_SUPPLIER', label: 'Sent' },
  { key: 'PARTIALLY_RECEIVED', label: 'Partially Received' },
  { key: 'RECEIVED', label: 'Received' },
  { key: 'CLOSED', label: 'Closed' },
  { key: 'REJECTED', label: 'Rejected' },
  { key: 'CANCELLED', label: 'Cancelled' },
]

function friendlyError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 409 || err.errorCode === 'VERSION_CONFLICT') {
      return 'This purchase order was changed elsewhere - refresh and try again.'
    }
    return err.message || fallback
  }
  return fallback
}

/** Bistrodesk Phase 9 (requirement #19-22): the same "open a wa.me deep link with the phone and
 * text pre-filled" approach chefpay-javafx's WhatsAppSender already uses for receipts - genuinely
 * works today with zero setup (no WhatsApp Business API account needed), one click short of a
 * fully automated send. `phone` should include a country code (digits only) - wa.me silently fails
 * to resolve a chat otherwise. */
function openWhatsApp(phone: string, text: string): boolean {
  const digitsOnly = phone.replace(/[^0-9]/g, '')
  if (!digitsOnly) return false
  const url = `https://wa.me/${digitsOnly}?text=${encodeURIComponent(text)}`
  const win = window.open(url, '_blank', 'noopener,noreferrer')
  return win != null
}

/** Opens a plain-text PO document in a new tab and triggers the browser's print dialog - the web
 * equivalent of chefpay-javafx's ReceiptPrinter/Print action, no server-side PDF generation needed
 * since the document is already just formatted plain text (see PurchaseOrderService
 * #generateDocumentText's javadoc for why one plain-text document backs Print/Email/WhatsApp/API
 * identically). */
function printDocument(poNumber: string, text: string) {
  const win = window.open('', '_blank')
  if (!win) return
  win.document.write(
    `<title>PO ${poNumber}</title><pre style="font-family: ui-monospace, monospace; font-size: 13px; white-space: pre-wrap;">${text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')}</pre>`,
  )
  win.document.close()
  win.focus()
  win.print()
}

interface DraftLine {
  key: string
  inventoryItemId: string
  orderedQuantity: string
  unitPrice: string
}

function emptyLine(): DraftLine {
  return { key: crypto.randomUUID(), inventoryItemId: '', orderedQuantity: '', unitPrice: '' }
}

/** Shared line-item editor for both the create form and the replenishment-to-draft flow - a plain
 * table of inventory item / quantity / unit price rows, since a PO's item list is always a full
 * replace on save (UpdatePurchaseOrderRequest's own javadoc) rather than a line-level patch API. */
function ItemsEditor({
  lines,
  onChange,
  items,
}: {
  lines: DraftLine[]
  onChange: (lines: DraftLine[]) => void
  items: InventoryItemDto[]
}) {
  const update = (key: string, patch: Partial<DraftLine>) =>
    onChange(lines.map((l) => (l.key === key ? { ...l, ...patch } : l)))
  const remove = (key: string) => onChange(lines.filter((l) => l.key !== key))

  return (
    <div className="space-y-2">
      <div className="overflow-x-auto rounded-lg border border-app">
        <table className="w-full text-xs">
          <thead className="bg-app/50 text-left text-muted">
            <tr>
              <th className="px-2 py-1.5">Item</th>
              <th className="px-2 py-1.5">Qty</th>
              <th className="px-2 py-1.5">Unit Price</th>
              <th className="px-2 py-1.5 text-right">Total</th>
              <th className="w-8"></th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line) => {
              const item = items.find((i) => i.id === line.inventoryItemId)
              const total = (Number(line.orderedQuantity) || 0) * (Number(line.unitPrice) || 0)
              return (
                <tr key={line.key} className="border-t border-app">
                  <td className="px-2 py-1.5">
                    <select
                      value={line.inventoryItemId}
                      onChange={(e) => update(line.key, { inventoryItemId: e.target.value })}
                      className="w-full rounded-lg border border-app bg-app px-2 py-1 text-xs text-app outline-none"
                    >
                      <option value="">Select item…</option>
                      {items.map((i) => (
                        <option key={i.id} value={i.id}>
                          {i.name} ({i.unit})
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="px-2 py-1.5">
                    <input
                      type="number"
                      min={0}
                      step="any"
                      value={line.orderedQuantity}
                      onChange={(e) => update(line.key, { orderedQuantity: e.target.value })}
                      placeholder={item?.unit ?? 'qty'}
                      className="w-20 rounded-lg border border-app bg-app px-2 py-1 text-xs text-app outline-none"
                    />
                  </td>
                  <td className="px-2 py-1.5">
                    <input
                      type="number"
                      min={0}
                      step="any"
                      value={line.unitPrice}
                      onChange={(e) => update(line.key, { unitPrice: e.target.value })}
                      className="w-24 rounded-lg border border-app bg-app px-2 py-1 text-xs text-app outline-none"
                    />
                  </td>
                  <td className="px-2 py-1.5 text-right font-semibold text-app">{formatCurrency(total)}</td>
                  <td className="px-1 py-1.5">
                    <button type="button" onClick={() => remove(line.key)} className="rounded p-1 text-muted hover:bg-danger-soft hover:text-danger">
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <Button type="button" size="sm" variant="secondary" onClick={() => onChange([...lines, emptyLine()])}>
        <Plus className="h-3.5 w-3.5" /> Add Item
      </Button>
    </div>
  )
}

// ---- Create / Edit modal ----

function PoFormModal({
  open,
  onClose,
  editing,
  suppliers,
  items,
  branches,
  defaultBranchId,
  prefillItems,
  prefillSupplierId,
}: {
  open: boolean
  onClose: () => void
  editing: PurchaseOrderDto | null
  suppliers: SupplierDto[]
  items: InventoryItemDto[]
  branches: AccessibleBranchDto[]
  defaultBranchId?: string
  prefillItems?: DraftLine[]
  prefillSupplierId?: string
}) {
  const queryClient = useQueryClient()
  const [branchId, setBranchId] = useState(editing?.branchId ?? defaultBranchId ?? branches[0]?.id ?? '')
  const [supplierId, setSupplierId] = useState(editing?.supplierId ?? prefillSupplierId ?? '')
  const [notes, setNotes] = useState(editing?.notes ?? '')
  const [lines, setLines] = useState<DraftLine[]>(
    editing
      ? editing.items.map((i) => ({
          key: i.id,
          inventoryItemId: i.inventoryItemId,
          orderedQuantity: String(i.orderedQuantity),
          unitPrice: String(i.unitPrice),
        }))
      : (prefillItems ?? [emptyLine()]),
  )
  const [error, setError] = useState<string | null>(null)

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['purchase-orders'] })

  const toItemRequests = (): CreatePurchaseOrderItemRequest[] =>
    lines
      .filter((l) => l.inventoryItemId && Number(l.orderedQuantity) > 0)
      .map((l) => ({ inventoryItemId: l.inventoryItemId, orderedQuantity: Number(l.orderedQuantity), unitPrice: Number(l.unitPrice) || 0 }))

  const create = useMutation({
    mutationFn: () =>
      api.post<PurchaseOrderDto>('/purchasing/purchase-orders', {
        branchId,
        supplierId,
        notes: notes || null,
        items: toItemRequests(),
      } satisfies CreatePurchaseOrderRequest),
    onSuccess: () => {
      invalidate()
      onClose()
    },
    onError: (err) => setError(friendlyError(err, 'Could not create this purchase order')),
  })

  const update = useMutation({
    mutationFn: () => {
      if (!editing) throw new Error('no purchase order selected')
      return api.patch<PurchaseOrderDto>(`/purchasing/purchase-orders/${editing.id}`, {
        supplierId,
        notes: notes || null,
        items: toItemRequests(),
        version: editing.version,
      } satisfies UpdatePurchaseOrderRequest)
    },
    onSuccess: () => {
      invalidate()
      onClose()
    },
    onError: (err) => setError(friendlyError(err, 'Could not save changes')),
  })

  const pending = create.isPending || update.isPending
  const canSubmit = supplierId && (editing ? true : branchId) && toItemRequests().length > 0

  return (
    <Modal open={open} onClose={onClose} title={editing ? `Edit ${editing.poNumber}` : 'New Purchase Order'} widthClassName="max-w-2xl">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          if (editing) update.mutate()
          else create.mutate()
        }}
      >
        <div className="grid grid-cols-2 gap-3">
          {!editing && (
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
          <div className={editing ? 'col-span-2' : ''}>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Supplier</label>
            <select
              required
              value={supplierId}
              onChange={(e) => setSupplierId(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            >
              <option value="">Select supplier…</option>
              {suppliers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Items</label>
          <ItemsEditor lines={lines} onChange={setLines} items={items} />
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Notes (optional)</label>
          <textarea
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            rows={2}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>

        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

        <Button type="submit" className="w-full" disabled={pending || !canSubmit}>
          {pending ? 'Saving…' : editing ? 'Save changes' : 'Create Draft'}
        </Button>
      </form>
    </Modal>
  )
}

// ---- Reject modal ----

function RejectModal({ po, onClose, onDone }: { po: PurchaseOrderDto; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const reject = useMutation({
    mutationFn: () =>
      api.post<PurchaseOrderDto>(`/purchasing/purchase-orders/${po.id}/reject`, {
        reason: reason || null,
        version: po.version,
      } satisfies RejectPurchaseOrderRequest),
    onSuccess: onDone,
    onError: (err) => setError(friendlyError(err, 'Could not reject this purchase order')),
  })

  return (
    <Modal open onClose={onClose} title={`Reject ${po.poNumber}?`} widthClassName="max-w-sm">
      <div className="space-y-4">
        <textarea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={3}
          placeholder="Reason (optional, shown to whoever submitted it)"
          className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
        />
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" className="flex-1" disabled={reject.isPending} onClick={() => reject.mutate()}>
            {reject.isPending ? 'Rejecting…' : 'Reject'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// ---- Share modal (Print / Email / WhatsApp) ----

function ShareModal({ po, onClose, onDone }: { po: PurchaseOrderDto; onClose: () => void; onDone: () => void }) {
  const [method, setMethod] = useState<'PRINT' | 'EMAIL' | 'WHATSAPP'>('WHATSAPP')
  const supplierPhoneQuery = useQuery({ queryKey: ['suppliers', po.supplierId], queryFn: () => api.get<SupplierDto>(`/suppliers/${po.supplierId}`) })
  const [recipient, setRecipient] = useState('')
  const [error, setError] = useState<string | null>(null)

  const documentQuery = useQuery({
    queryKey: ['purchase-orders', po.id, 'document'],
    queryFn: () => api.get<string>(`/purchasing/purchase-orders/${po.id}/document`),
  })

  useEffect(() => {
    const supplier = supplierPhoneQuery.data
    if (!supplier) return
    setRecipient(method === 'EMAIL' ? supplier.email ?? '' : supplier.phone ?? '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [method, supplierPhoneQuery.data])

  const logShare = useMutation({
    mutationFn: (m: 'PRINT' | 'EMAIL' | 'WHATSAPP') =>
      api.post<PurchaseOrderDto>(`/purchasing/purchase-orders/${po.id}/share`, {
        method: m,
        recipient: recipient || null,
        version: po.version,
      } satisfies ShareRequest),
    onSuccess: onDone,
    onError: (err) => setError(friendlyError(err, 'Could not share this purchase order')),
  })

  const send = () => {
    setError(null)
    const text = documentQuery.data ?? ''
    if (method === 'PRINT') {
      printDocument(po.poNumber, text)
      logShare.mutate('PRINT')
      return
    }
    if (method === 'WHATSAPP') {
      if (!recipient.trim()) {
        setError('Enter a phone number (with country code) first.')
        return
      }
      // Bistrodesk follow-up requirement #4 (WhatsApp integration): once this branch has a real
      // WhatsApp Business API configured (Branch#whatsappProvider - see BranchesTerminalsPage's
      // "WhatsApp Business API" section), the server can send the message itself, so there's no
      // reason to also pop open the WhatsApp app - that's only the fallback for an unconfigured
      // branch. logShare's own mutationFn hits PurchaseOrderService#shareWithSupplier, which does
      // the real send and throws WHATSAPP_API_UNAVAILABLE (surfaced by onError below) on failure.
      if (!po.whatsappApiConfigured) {
        const opened = openWhatsApp(recipient, text)
        if (!opened) {
          setError("Couldn't open WhatsApp - check your browser's pop-up blocker.")
          return
        }
      }
      logShare.mutate('WHATSAPP')
      return
    }
    if (!recipient.trim()) {
      setError('Enter an email address first.')
      return
    }
    logShare.mutate('EMAIL')
  }

  return (
    <Modal open onClose={onClose} title={`Share ${po.poNumber}`} widthClassName="max-w-md">
      <div className="space-y-4">
        <div className="flex gap-1 rounded-xl border border-app bg-app p-1 text-sm font-semibold">
          {(['WHATSAPP', 'EMAIL', 'PRINT'] as const).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMethod(m)}
              className={cn('flex flex-1 items-center justify-center gap-1.5 rounded-lg px-3 py-1.5 text-xs transition-colors', method === m ? 'bg-brand-600 text-white' : 'text-muted hover:bg-surface')}
            >
              {m === 'PRINT' && <Printer className="h-3.5 w-3.5" />}
              {m === 'WHATSAPP' ? 'WhatsApp' : m === 'EMAIL' ? 'Email' : 'Print'}
            </button>
          ))}
        </div>

        {documentQuery.isLoading && (
          <div className="flex items-center gap-2 text-xs text-muted">
            <Spinner className="h-3.5 w-3.5" /> <FileText className="h-3.5 w-3.5" /> Loading the PO document…
          </div>
        )}

        {method !== 'PRINT' && (
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">
              {method === 'EMAIL' ? 'Email address' : 'Phone number (with country code)'}
            </label>
            <input
              value={recipient}
              onChange={(e) => setRecipient(e.target.value)}
              placeholder={method === 'EMAIL' ? 'supplier@example.com' : 'e.g. 91 98765 43210'}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
        )}

        <p className="text-xs text-muted">
          {method === 'PRINT' && 'Opens the PO document in a new tab and starts your browser\'s print dialog.'}
          {method === 'EMAIL' && "Sends the PO document by email directly - no click needed on the supplier's end."}
          {method === 'WHATSAPP' &&
            (po.whatsappApiConfigured
              ? 'Sends the PO document straight to this number via your configured WhatsApp Business API - no extra click needed.'
              : "Opens WhatsApp with the PO document pre-filled in the chat - one click (Send) on your end to actually deliver it. Configure a WhatsApp Business API for this branch (Branches & Terminals) to send this automatically instead.")}
        </p>

        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button className="flex-1" disabled={logShare.isPending || documentQuery.isLoading} onClick={send}>
            {logShare.isPending ? 'Sending…' : method === 'PRINT' ? 'Print' : 'Send'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// ---- Receive modal ----

interface ReceiveLine {
  purchaseOrderItemId: string
  received: string
  damaged: string
  rejected: string
  notes: string
}

function ReceiveModal({ po, onClose, onDone }: { po: PurchaseOrderDto; onClose: () => void; onDone: () => void }) {
  const receivable = po.items.filter((i) => i.remainingQuantity > 0)
  const [lines, setLines] = useState<Record<string, ReceiveLine>>(
    Object.fromEntries(receivable.map((i) => [i.id, { purchaseOrderItemId: i.id, received: '', damaged: '0', rejected: '0', notes: '' }])),
  )
  const [error, setError] = useState<string | null>(null)

  const receive = useMutation({
    mutationFn: () => {
      const body: ReceiveItemsRequest = {
        version: po.version,
        lines: Object.values(lines)
          .filter((l) => Number(l.received) > 0)
          .map((l) => {
            const received = Number(l.received) || 0
            const damaged = Number(l.damaged) || 0
            const rejected = Number(l.rejected) || 0
            return {
              purchaseOrderItemId: l.purchaseOrderItemId,
              receivedQuantity: received,
              acceptedQuantity: Math.max(0, received - damaged - rejected),
              damagedQuantity: damaged,
              rejectedQuantity: rejected,
              notes: l.notes || null,
            }
          }),
      }
      return api.post<PurchaseOrderDto>(`/purchasing/purchase-orders/${po.id}/receive`, body)
    },
    onSuccess: onDone,
    onError: (err) => setError(friendlyError(err, 'Could not record receiving')),
  })

  const update = (id: string, patch: Partial<ReceiveLine>) => setLines((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }))
  const anyEntered = Object.values(lines).some((l) => Number(l.received) > 0)

  return (
    <Modal open onClose={onClose} title={`Receive against ${po.poNumber}`} widthClassName="max-w-2xl">
      <div className="space-y-4">
        <p className="text-xs text-muted">
          Enter what arrived in THIS delivery only - not the running total. Accepted is calculated as received minus damaged minus rejected.
        </p>
        <div className="space-y-3">
          {receivable.map((item) => {
            const line = lines[item.id]
            return (
              <div key={item.id} className="rounded-xl border border-app p-3">
                <div className="mb-2 flex items-center justify-between text-sm font-semibold text-app">
                  <span>{item.inventoryItemName}</span>
                  <span className="text-xs font-normal text-muted">
                    Remaining: {item.remainingQuantity} {item.unit}
                  </span>
                </div>
                <div className="grid grid-cols-3 gap-2">
                  <div>
                    <label className="mb-1 block text-[11px] font-semibold text-muted">Received</label>
                    <input
                      type="number"
                      min={0}
                      step="any"
                      value={line.received}
                      onChange={(e) => update(item.id, { received: e.target.value })}
                      className="w-full rounded-lg border border-app bg-app px-2 py-1.5 text-sm text-app outline-none"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-[11px] font-semibold text-muted">Damaged</label>
                    <input
                      type="number"
                      min={0}
                      step="any"
                      value={line.damaged}
                      onChange={(e) => update(item.id, { damaged: e.target.value })}
                      className="w-full rounded-lg border border-app bg-app px-2 py-1.5 text-sm text-app outline-none"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-[11px] font-semibold text-muted">Rejected</label>
                    <input
                      type="number"
                      min={0}
                      step="any"
                      value={line.rejected}
                      onChange={(e) => update(item.id, { rejected: e.target.value })}
                      className="w-full rounded-lg border border-app bg-app px-2 py-1.5 text-sm text-app outline-none"
                    />
                  </div>
                </div>
                <input
                  value={line.notes}
                  onChange={(e) => update(item.id, { notes: e.target.value })}
                  placeholder="Notes (optional)"
                  className="mt-2 w-full rounded-lg border border-app bg-app px-2 py-1.5 text-xs text-app outline-none"
                />
              </div>
            )
          })}
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button className="flex-1" disabled={receive.isPending || !anyEntered} onClick={() => receive.mutate()}>
            {receive.isPending ? 'Recording…' : 'Record Receiving'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// ---- Detail modal ----

function PoDetailModal({
  po,
  onClose,
  canModify,
  canApprove,
  canReject,
  canCancel,
  canReceive,
  supportPhone,
  onEdit,
}: {
  po: PurchaseOrderDto
  onClose: () => void
  canModify: boolean
  canApprove: boolean
  canReject: boolean
  canCancel: boolean
  canReceive: boolean
  supportPhone: string | null
  onEdit: () => void
}) {
  const queryClient = useQueryClient()
  const [shareOpen, setShareOpen] = useState(false)
  const [receiveOpen, setReceiveOpen] = useState(false)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const shareLogQuery = useQuery({
    queryKey: ['purchase-orders', po.id, 'share-log'],
    queryFn: () => api.get<ShareLogDto[]>(`/purchasing/purchase-orders/${po.id}/share-log`),
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['purchase-orders'] })

  const submit = useMutation({
    mutationFn: () => api.post<PurchaseOrderDto>(`/purchasing/purchase-orders/${po.id}/submit`, undefined, { version: po.version }),
    onSuccess: invalidate,
    onError: (err) => setActionError(friendlyError(err, 'Could not submit this purchase order')),
  })
  const approve = useMutation({
    mutationFn: () => api.post<PurchaseOrderDto>(`/purchasing/purchase-orders/${po.id}/approve`, undefined, { version: po.version }),
    onSuccess: invalidate,
    onError: (err) => setActionError(friendlyError(err, 'Could not approve this purchase order')),
  })
  const cancel = useMutation({
    mutationFn: () => api.post<PurchaseOrderDto>(`/purchasing/purchase-orders/${po.id}/cancel`, undefined, { version: po.version }),
    onSuccess: invalidate,
    onError: (err) => setActionError(friendlyError(err, 'Could not cancel this purchase order')),
  })
  const close = useMutation({
    mutationFn: () => api.post<PurchaseOrderDto>(`/purchasing/purchase-orders/${po.id}/close`, undefined, { version: po.version }),
    onSuccess: invalidate,
    onError: (err) => setActionError(friendlyError(err, 'Could not close this purchase order')),
  })

  const notifyOwner = () => {
    if (!supportPhone) return
    const text = `Purchase Order ${po.poNumber} (${STATUS_LABEL[po.status]}) - ${po.supplierName} - Total ${formatCurrency(po.totalAmount)}`
    openWhatsApp(supportPhone, text)
  }

  return (
    <>
      <Modal open onClose={onClose} title={po.poNumber} widthClassName="max-w-2xl">
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <Badge tone={STATUS_TONE[po.status]}>{STATUS_LABEL[po.status]}</Badge>
              <div className="mt-1.5 text-sm font-semibold text-app">{po.supplierName}</div>
              <div className="text-xs text-muted">{po.branchName}</div>
            </div>
            <div className="text-right text-sm">
              <div className="font-bold text-app">{formatCurrency(po.totalAmount)}</div>
              <div className="text-xs text-muted">{formatDateTime(po.createdAt)}</div>
            </div>
          </div>

          {po.status === 'REJECTED' && po.rejectionReason && (
            <div className="rounded-lg bg-danger-soft px-3 py-2 text-xs text-danger">Rejected: {po.rejectionReason}</div>
          )}

          <div className="overflow-x-auto rounded-lg border border-app">
            <table className="w-full text-xs">
              <thead className="bg-app/50 text-left text-muted">
                <tr>
                  <th className="px-2 py-1.5">Item</th>
                  <th className="px-2 py-1.5">Ordered</th>
                  <th className="px-2 py-1.5">Received</th>
                  <th className="px-2 py-1.5 text-right">Total</th>
                </tr>
              </thead>
              <tbody>
                {po.items.map((item) => (
                  <tr key={item.id} className="border-t border-app">
                    <td className="px-2 py-1.5 text-app">{item.inventoryItemName}</td>
                    <td className="px-2 py-1.5 text-muted">
                      {item.orderedQuantity} {item.unit}
                    </td>
                    <td className="px-2 py-1.5 text-muted">
                      {item.receivedQuantity} {item.unit}
                    </td>
                    <td className="px-2 py-1.5 text-right font-semibold text-app">{formatCurrency(item.lineTotal)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {po.notes && <p className="rounded-lg bg-app/40 px-3 py-2 text-xs text-muted">{po.notes}</p>}

          {shareLogQuery.data && shareLogQuery.data.length > 0 && (
            <div>
              <div className="mb-1 text-[11px] font-bold uppercase tracking-wide text-muted">Share History</div>
              <ul className="space-y-1 text-xs text-muted">
                {shareLogQuery.data.map((log) => (
                  <li key={log.id} className="flex items-center justify-between rounded-lg bg-app/30 px-2 py-1">
                    <span>
                      {log.method} → {log.recipient ?? '—'}
                    </span>
                    <span>{formatDateTime(log.sentAt)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {actionError && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{actionError}</div>}

          <div className="flex flex-wrap gap-2 border-t border-app pt-3">
            {po.status === 'DRAFT' && canModify && (
              <Button size="sm" variant="secondary" onClick={onEdit}>
                Edit
              </Button>
            )}
            {po.status === 'DRAFT' && canModify && (
              <Button size="sm" disabled={submit.isPending} onClick={() => submit.mutate()}>
                <Send className="h-3.5 w-3.5" /> Submit for Approval
              </Button>
            )}
            {po.status === 'PENDING_APPROVAL' && canApprove && (
              <Button size="sm" disabled={approve.isPending} onClick={() => approve.mutate()}>
                <CheckCircle2 className="h-3.5 w-3.5" /> Approve
              </Button>
            )}
            {po.status === 'PENDING_APPROVAL' && canReject && (
              <Button size="sm" variant="danger" onClick={() => setRejectOpen(true)}>
                <XCircle className="h-3.5 w-3.5" /> Reject
              </Button>
            )}
            {(po.status === 'APPROVED' || po.status === 'SENT_TO_SUPPLIER' || po.status === 'PARTIALLY_RECEIVED') && (
              <Button size="sm" variant="secondary" onClick={() => setShareOpen(true)}>
                <Send className="h-3.5 w-3.5" /> Share with Supplier
              </Button>
            )}
            {(po.status === 'APPROVED' || po.status === 'SENT_TO_SUPPLIER' || po.status === 'PARTIALLY_RECEIVED') && canReceive && (
              <Button size="sm" variant="secondary" onClick={() => setReceiveOpen(true)}>
                <Package className="h-3.5 w-3.5" /> Receive
              </Button>
            )}
            {po.status === 'RECEIVED' && canApprove && (
              <Button size="sm" disabled={close.isPending} onClick={() => close.mutate()}>
                Close
              </Button>
            )}
            {supportPhone && (
              <Button size="sm" variant="secondary" onClick={notifyOwner}>
                <Phone className="h-3.5 w-3.5" /> Notify Owner
              </Button>
            )}
            {canCancel && !['CLOSED', 'CANCELLED', 'REJECTED', 'RECEIVED'].includes(po.status) && (
              <Button size="sm" variant="ghost" className="ml-auto text-danger" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
                Cancel PO
              </Button>
            )}
          </div>
        </div>
      </Modal>

      {shareOpen && (
        <ShareModal
          po={po}
          onClose={() => setShareOpen(false)}
          onDone={() => {
            setShareOpen(false)
            invalidate()
          }}
        />
      )}
      {receiveOpen && (
        <ReceiveModal
          po={po}
          onClose={() => setReceiveOpen(false)}
          onDone={() => {
            setReceiveOpen(false)
            invalidate()
            onClose()
          }}
        />
      )}
      {rejectOpen && (
        <RejectModal
          po={po}
          onClose={() => setRejectOpen(false)}
          onDone={() => {
            setRejectOpen(false)
            invalidate()
            onClose()
          }}
        />
      )}
    </>
  )
}

// ---- Replenishment suggestions ----

function ReplenishmentPanel({
  suppliers,
  onCreateDraft,
}: {
  suppliers: SupplierDto[]
  onCreateDraft: (supplierId: string, lines: DraftLine[]) => void
}) {
  const [seasonal, setSeasonal] = useState(false)
  const [supplierId, setSupplierId] = useState(suppliers[0]?.id ?? '')
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const suggestionsQuery = useQuery({
    queryKey: ['purchase-orders', 'replenishment', seasonal],
    queryFn: () =>
      api.get<ReplenishmentSuggestionsResponse>(
        seasonal ? '/purchasing/replenishment-suggestions/seasonal' : '/purchasing/replenishment-suggestions',
      ),
  })

  const suggestions = suggestionsQuery.data?.suggestions ?? []

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const createDraft = () => {
    const lines: DraftLine[] = suggestions
      .filter((s) => selected.has(s.inventoryItemId))
      .map((s) => ({ key: s.inventoryItemId, inventoryItemId: s.inventoryItemId, orderedQuantity: String(s.suggestedQuantity), unitPrice: '' }))
    if (lines.length === 0 || !supplierId) return
    onCreateDraft(supplierId, lines)
  }

  if (suggestionsQuery.isLoading) return <FullPageSpinner label="Loading suggestions…" />

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <label className="flex items-center gap-2 text-sm text-app">
          <input type="checkbox" checked={seasonal} onChange={(e) => setSeasonal(e.target.checked)} />
          Weight by day-of-week seasonality
        </label>
        <select
          value={supplierId}
          onChange={(e) => setSupplierId(e.target.value)}
          className="rounded-lg border border-app bg-app px-2.5 py-1.5 text-sm text-app outline-none"
        >
          <option value="">Assign draft to supplier…</option>
          {suppliers.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </div>

      {suggestionsQuery.data?.aiNarrative && (
        <div className="flex items-start gap-2 rounded-xl bg-brand-50 px-3 py-2.5 text-xs text-brand-800 dark:bg-brand-900/30 dark:text-brand-200">
          <Sparkles className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>{suggestionsQuery.data.aiNarrative}</span>
        </div>
      )}

      {suggestions.length === 0 ? (
        <div className="flex h-40 flex-col items-center justify-center gap-2 text-muted">
          <Package className="h-6 w-6" />
          <span className="text-sm font-medium">Nothing below its reorder threshold right now</span>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-app">
          <table className="w-full text-xs">
            <thead className="bg-app/50 text-left text-muted">
              <tr>
                <th className="w-8 px-2 py-2"></th>
                <th className="px-2 py-2">Item</th>
                <th className="px-2 py-2">On Hand</th>
                <th className="px-2 py-2">Threshold</th>
                <th className="px-2 py-2">Suggested Qty</th>
                <th className="px-2 py-2">Why</th>
              </tr>
            </thead>
            <tbody>
              {suggestions.map((s) => (
                <tr key={s.inventoryItemId} className="border-t border-app">
                  <td className="px-2 py-2">
                    <input type="checkbox" checked={selected.has(s.inventoryItemId)} onChange={() => toggle(s.inventoryItemId)} />
                  </td>
                  <td className="px-2 py-2 font-medium text-app">{s.itemName}</td>
                  <td className="px-2 py-2 text-muted">
                    {s.quantityOnHand} {s.unit}
                  </td>
                  <td className="px-2 py-2 text-muted">{s.reorderThreshold ?? '—'}</td>
                  <td className="px-2 py-2 font-semibold text-app">
                    {s.suggestedQuantity} {s.unit}
                  </td>
                  <td className="px-2 py-2 text-muted">{s.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Button size="sm" disabled={selected.size === 0 || !supplierId} onClick={createDraft}>
        <Plus className="h-3.5 w-3.5" /> Create Draft PO from {selected.size || ''} Selected
      </Button>
    </div>
  )
}

// ---- Suppliers ----
//
// Bistrodesk branch-isolation release (requirement #6): relocated here, verbatim, from
// InventoryPage.tsx's SuppliersTab - Supplier CRUD is fully built (SupplierController/
// SupplierService/SupplierRepository) but was only reachable via a tab buried inside the
// Inventory screen, which reads as "removed" to a user looking for it under Purchase Orders. Same
// /suppliers API, same SUPPLIER_MANAGE gate, same branch-picker logic (Phase 3: Supplier is now
// strictly branch-owned) as before the move - only its home page changed.

/** A supplier now belongs to exactly one branch, resolved server-side (a single-branch caller or
 * one with a default branch never needs to choose) - the branch picker below only renders when
 * {@code branches} has more than one entry, same convention as CustomersPage's CustomerModal. */
function SupplierFormModal({
  supplier,
  onClose,
  branches,
}: {
  supplier: SupplierDto | null
  onClose: () => void
  branches: AccessibleBranchDto[]
}) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(supplier?.name ?? '')
  const [contactPerson, setContactPerson] = useState(supplier?.contactPerson ?? '')
  const [phone, setPhone] = useState(supplier?.phone ?? '')
  const [email, setEmail] = useState(supplier?.email ?? '')
  const [address, setAddress] = useState(supplier?.address ?? '')
  const [notes, setNotes] = useState(supplier?.notes ?? '')
  const [branchId, setBranchId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const showBranchPicker = !supplier && branches.length > 1

  const save = useMutation({
    mutationFn: () => {
      const payload = {
        name: name.trim(),
        contactPerson: contactPerson.trim() || null,
        phone: phone.trim() || null,
        email: email.trim() || null,
        address: address.trim() || null,
        notes: notes.trim() || null,
      }
      if (supplier) {
        return api.patch<SupplierDto>(`/suppliers/${supplier.id}`, {
          ...payload,
          version: supplier.version,
        } satisfies UpdateSupplierRequest)
      }
      return api.post<SupplierDto>('/suppliers', { ...payload, branchId: branchId || null } satisfies CreateSupplierRequest)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['suppliers'] })
      onClose()
    },
    onError: (err) => setError(friendlyError(err, `Could not ${supplier ? 'update' : 'create'} this supplier`)),
  })

  return (
    <Modal open onClose={onClose} title={supplier ? `Edit ${supplier.name}` : 'Add Supplier'} widthClassName="max-w-md">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          save.mutate()
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
            <label className="mb-1.5 block text-xs font-semibold text-muted">Contact person</label>
            <input
              value={contactPerson ?? ''}
              onChange={(e) => setContactPerson(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Phone</label>
            <input
              value={phone ?? ''}
              onChange={(e) => setPhone(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Email</label>
          <input
            type="email"
            value={email ?? ''}
            onChange={(e) => setEmail(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Address</label>
          <textarea
            rows={2}
            value={address ?? ''}
            onChange={(e) => setAddress(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Notes</label>
          <textarea
            rows={2}
            value={notes ?? ''}
            onChange={(e) => setNotes(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <Button type="submit" className="w-full" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : supplier ? 'Save changes' : 'Add supplier'}
        </Button>
      </form>
    </Modal>
  )
}

function SuppliersTab({ canManageSuppliers }: { canManageSuppliers: boolean }) {
  const [formTarget, setFormTarget] = useState<SupplierDto | 'new' | null>(null)

  const suppliersQuery = useQuery({
    queryKey: ['suppliers'],
    queryFn: () => api.get<SupplierDto[]>('/suppliers'),
  })

  // Only fetched to decide whether the Add Supplier form's branch picker should render - same
  // convention as CustomersPage.
  const branchesQuery = useQuery({
    queryKey: ['branches', 'accessible'],
    queryFn: () => api.get<AccessibleBranchDto[]>('/branches/accessible'),
  })
  const branches = branchesQuery.data ?? []

  const suppliers = suppliersQuery.data ?? []

  if (suppliersQuery.isLoading) return <FullPageSpinner label="Loading suppliers…" />

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted">Vendors used to restock inventory items and place purchase orders.</p>
        {canManageSuppliers && (
          <Button size="sm" onClick={() => setFormTarget('new')}>
            <Plus className="h-3.5 w-3.5" /> Add Supplier
          </Button>
        )}
      </div>

      {suppliersQuery.isError && (
        <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
          {friendlyError(suppliersQuery.error, 'Could not load suppliers')}
        </div>
      )}

      {suppliers.length === 0 ? (
        <div className="flex h-48 flex-col items-center justify-center gap-2 text-muted">
          <Truck className="h-8 w-8" />
          <span className="text-sm font-medium">No suppliers yet</span>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {suppliers.map((s) => (
            <Card key={s.id} className="p-4">
              <div className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-2">
                  <Building2 className="h-4 w-4 shrink-0 text-brand-600" />
                  <span className="text-sm font-bold text-app">{s.name}</span>
                </div>
                <div className="flex items-center gap-1">
                  {!s.active && <Badge tone="neutral">Inactive</Badge>}
                  {canManageSuppliers && (
                    <button
                      type="button"
                      title="Edit supplier"
                      className="rounded-lg p-1.5 text-muted hover:bg-app"
                      onClick={() => setFormTarget(s)}
                    >
                      <Pencil className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
              </div>
              <div className="mt-3 space-y-1 text-xs text-muted">
                {s.contactPerson && <div>{s.contactPerson}</div>}
                {s.phone && (
                  <div className="flex items-center gap-1.5">
                    <Phone className="h-3 w-3" /> {s.phone}
                  </div>
                )}
                {s.email && (
                  <div className="flex items-center gap-1.5">
                    <Mail className="h-3 w-3" /> {s.email}
                  </div>
                )}
                {s.address && <div>{s.address}</div>}
                {!s.contactPerson && !s.phone && !s.email && !s.address && <div>No contact details on file.</div>}
              </div>
            </Card>
          ))}
        </div>
      )}

      {formTarget && (
        <SupplierFormModal supplier={formTarget === 'new' ? null : formTarget} onClose={() => setFormTarget(null)} branches={branches} />
      )}
    </div>
  )
}

// ---- Main page ----

export function PurchaseOrdersPage() {
  const { hasPermission } = useAuthStore()
  const { isEnabled: isFeatureEnabled, isLoading: entitlementsLoading } = useEntitlements()

  const canView = hasPermission('PURCHASE_ORDER_VIEW') || hasPermission('PURCHASE_ORDER_CREATE') || hasPermission('PURCHASE_ORDER_APPROVE')
  const canCreate = hasPermission('PURCHASE_ORDER_CREATE')
  const canModify = hasPermission('PURCHASE_ORDER_MODIFY')
  const canApprove = hasPermission('PURCHASE_ORDER_APPROVE')
  const canReject = hasPermission('PURCHASE_ORDER_REJECT') || canApprove
  const canCancel = hasPermission('PURCHASE_ORDER_CANCEL')
  const canReceive = hasPermission('PURCHASE_ORDER_RECEIVE')
  const canManageSuppliers = hasPermission('SUPPLIER_MANAGE')

  const [view, setView] = useState<'orders' | 'replenishment' | 'suppliers'>('orders')
  const [statusFilter, setStatusFilter] = useState<'ALL' | PurchaseOrderStatus>('ALL')
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<PurchaseOrderDto | null>(null)
  const [prefill, setPrefill] = useState<{ supplierId: string; lines: DraftLine[] } | null>(null)
  const [detail, setDetail] = useState<PurchaseOrderDto | null>(null)

  const posQuery = useQuery({
    queryKey: ['purchase-orders'],
    queryFn: () => api.get<PurchaseOrderDto[]>('/purchasing/purchase-orders'),
    enabled: canView,
    refetchInterval: 30_000,
  })
  const suppliersQuery = useQuery({ queryKey: ['suppliers'], queryFn: () => api.get<SupplierDto[]>('/suppliers'), enabled: canView })
  const itemsQuery = useQuery({ queryKey: ['inventory', 'items'], queryFn: () => api.get<InventoryItemDto[]>('/inventory/items'), enabled: canView })
  const branchesQuery = useQuery({ queryKey: ['branches', 'accessible'], queryFn: () => api.get<AccessibleBranchDto[]>('/branches/accessible'), enabled: canView })
  const restaurantQuery = useQuery({
    queryKey: ['restaurant', 'for-purchasing'],
    queryFn: () => api.get<{ supportPhone: string | null }>('/restaurant'),
    enabled: canView,
    retry: false,
  })

  // Keep the detail modal's data fresh as the list refetches (a share/receive/approve action taken
  // from inside the detail modal should reflect in that same modal without needing to close/reopen).
  useEffect(() => {
    if (!detail) return
    const fresh = posQuery.data?.find((p) => p.id === detail.id)
    if (fresh && fresh.version !== detail.version) setDetail(fresh)
  }, [posQuery.data, detail])

  if (entitlementsLoading) return <FullPageSpinner label="Loading…" />

  if (!isFeatureEnabled('PURCHASE_ORDERS')) {
    return (
      <FeatureLockedScreen
        title="Not Included in Your Plan"
        message="Purchase Orders aren't part of this branch's current subscription plan. Contact Bistrodesk support to upgrade."
      />
    )
  }

  if (!canView) {
    return (
      <Card className="flex flex-col items-center justify-center gap-3 px-6 py-20 text-center">
        <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-danger-soft text-danger">
          <ShieldAlert className="h-7 w-7" />
        </div>
        <h2 className="text-lg font-bold text-app">Access Restricted</h2>
        <p className="max-w-sm text-sm text-muted">You don't have permission to view Purchase Orders.</p>
      </Card>
    )
  }

  const pos = posQuery.data ?? []
  const filtered = statusFilter === 'ALL' ? pos : pos.filter((p) => p.status === statusFilter)
  const suppliers = suppliersQuery.data ?? []
  const items = itemsQuery.data ?? []
  const branches = branchesQuery.data ?? []
  const supportPhone = restaurantQuery.data?.supportPhone ?? null

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold text-app">
            <ShoppingBag className="h-5 w-5 text-brand-600" /> Purchase Orders
          </h2>
          <p className="mt-0.5 text-sm text-muted">Order stock from suppliers, track approvals, receiving, and delivery sharing.</p>
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={() => posQuery.refetch()}>
            <RefreshCw className={cn('h-3.5 w-3.5', posQuery.isFetching && 'animate-spin')} /> Refresh
          </Button>
          {canCreate && view === 'orders' && (
            <Button
              size="sm"
              onClick={() => {
                setEditing(null)
                setPrefill(null)
                setFormOpen(true)
              }}
            >
              <Plus className="h-3.5 w-3.5" /> New Purchase Order
            </Button>
          )}
        </div>
      </div>

      {/* Replenishment Suggestions' own endpoint (GET /replenishment-suggestions) is gated server-side
          on CREATE/APPROVE/INVENTORY_MANAGE, not VIEW alone - hiding the tab for VIEW-only users avoids
          a confusing "nothing below threshold" empty state that's actually a swallowed 403. Suppliers,
          by contrast, is a plain directory with no such server-side gate on viewing it (SupplierController
          has no @RequiresFeature/extra permission beyond the page's own canView) - same as when it lived
          inside InventoryPage, it's shown to anyone who can reach this page at all. */}
      <div className="flex gap-1 rounded-xl border border-app bg-surface p-1 text-sm font-semibold w-fit">
        <button
          type="button"
          onClick={() => setView('orders')}
          className={cn('flex items-center gap-1.5 rounded-lg px-3 py-1.5 transition-colors', view === 'orders' ? 'bg-brand-600 text-white' : 'text-muted hover:bg-app')}
        >
          <ClipboardList className="h-3.5 w-3.5" /> Orders
        </button>
        {(canCreate || canApprove) && (
          <button
            type="button"
            onClick={() => setView('replenishment')}
            className={cn(
              'flex items-center gap-1.5 rounded-lg px-3 py-1.5 transition-colors',
              view === 'replenishment' ? 'bg-brand-600 text-white' : 'text-muted hover:bg-app',
            )}
          >
            <Truck className="h-3.5 w-3.5" /> Replenishment Suggestions
          </button>
        )}
        <button
          type="button"
          onClick={() => setView('suppliers')}
          className={cn(
            'flex items-center gap-1.5 rounded-lg px-3 py-1.5 transition-colors',
            view === 'suppliers' ? 'bg-brand-600 text-white' : 'text-muted hover:bg-app',
          )}
        >
          <Building2 className="h-3.5 w-3.5" /> Suppliers
        </button>
      </div>

      {view === 'suppliers' ? (
        <SuppliersTab canManageSuppliers={canManageSuppliers} />
      ) : view === 'replenishment' && (canCreate || canApprove) ? (
        <ReplenishmentPanel
          suppliers={suppliers}
          onCreateDraft={(supplierId, lines) => {
            setEditing(null)
            setPrefill({ supplierId, lines })
            setFormOpen(true)
          }}
        />
      ) : (
        <>
          <div className="flex flex-wrap gap-1.5">
            {STATUS_FILTERS.map((f) => (
              <button
                key={f.key}
                type="button"
                onClick={() => setStatusFilter(f.key)}
                className={cn(
                  'rounded-full px-3 py-1 text-xs font-semibold transition-colors',
                  statusFilter === f.key ? 'bg-brand-600 text-white' : 'bg-app text-muted hover:bg-app/70',
                )}
              >
                {f.label}
              </button>
            ))}
          </div>

          {posQuery.isLoading ? (
            <FullPageSpinner label="Loading purchase orders…" />
          ) : filtered.length === 0 ? (
            <div className="flex h-64 flex-col items-center justify-center gap-2 text-muted">
              <ShoppingBag className="h-8 w-8" />
              <span className="text-sm font-medium">No purchase orders {statusFilter === 'ALL' ? 'yet' : 'in this status'}</span>
            </div>
          ) : (
            <div className="overflow-x-auto rounded-2xl border border-app bg-surface">
              <table className="w-full text-sm">
                <thead className="bg-app/50 text-left text-xs uppercase tracking-wide text-muted">
                  <tr>
                    <th className="px-4 py-2.5">PO Number</th>
                    <th className="px-4 py-2.5">Supplier</th>
                    <th className="px-4 py-2.5">Branch</th>
                    <th className="px-4 py-2.5">Status</th>
                    <th className="px-4 py-2.5 text-right">Total</th>
                    <th className="px-4 py-2.5">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((po) => (
                    <tr key={po.id} onClick={() => setDetail(po)} className="cursor-pointer border-t border-app hover:bg-app/40">
                      <td className="px-4 py-3 font-semibold text-app">{po.poNumber}</td>
                      <td className="px-4 py-3 text-app">{po.supplierName}</td>
                      <td className="px-4 py-3 text-muted">{po.branchName}</td>
                      <td className="px-4 py-3">
                        <Badge tone={STATUS_TONE[po.status]}>{STATUS_LABEL[po.status]}</Badge>
                      </td>
                      <td className="px-4 py-3 text-right font-semibold text-app">{formatCurrency(po.totalAmount)}</td>
                      <td className="px-4 py-3 text-muted">{formatDateTime(po.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {formOpen && (
        <PoFormModal
          key={editing?.id ?? (prefill ? `prefill-${prefill.supplierId}` : 'new')}
          open={formOpen}
          onClose={() => {
            setFormOpen(false)
            setEditing(null)
            setPrefill(null)
          }}
          editing={editing}
          suppliers={suppliers}
          items={items}
          branches={branches}
          prefillItems={prefill?.lines}
          prefillSupplierId={prefill?.supplierId}
        />
      )}

      {detail && (
        <PoDetailModal
          po={detail}
          onClose={() => setDetail(null)}
          canModify={canModify}
          canApprove={canApprove}
          canReject={canReject}
          canCancel={canCancel}
          canReceive={canReceive}
          supportPhone={supportPhone}
          onEdit={() => {
            setEditing(detail)
            setPrefill(null)
            setDetail(null)
            setFormOpen(true)
          }}
        />
      )}
    </div>
  )
}
