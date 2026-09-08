import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ClipboardList,
  Mail,
  Phone,
  RefreshCw,
  Search,
  Sparkles,
  Star,
  UserPlus,
  Users,
} from 'lucide-react'
import { useEffect, useState } from 'react'

import { BranchSwitcher } from '@/components/common/BranchSwitcher'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Modal } from '@/components/ui/Modal'
import { FullPageSpinner, Spinner } from '@/components/ui/Spinner'
import { api } from '@/lib/api'
import { cn, formatCurrency, formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import { ApiError } from '@/types/api'
import type { AccessibleBranchDto, CreateCustomerRequest, CustomerDto, OrderDto, UpdateCustomerRequest } from '@/types/api'

/** Simple, non-overthought loyalty tagging purely for the directory grid - not a backend concept,
 * just a visual cue derived from the two counters CustomerDto already carries. VIP takes priority
 * over Regular when both thresholds are met. */
function getCustomerTag(customer: CustomerDto): { label: string; tone: 'brand' | 'info' } | null {
  if (customer.totalSpend >= 10_000 || customer.visitCount >= 25) return { label: 'VIP', tone: 'brand' }
  if (customer.visitCount >= 10) return { label: 'Regular', tone: 'info' }
  return null
}

function OrderList({ orders, note }: { orders: OrderDto[]; note: string }) {
  return (
    <div>
      <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wide text-muted">
        <ClipboardList className="h-3.5 w-3.5" /> Recent Orders
      </div>
      <p className="mb-2 text-[11px] text-muted">{note}</p>
      <ul className="max-h-40 space-y-1 overflow-y-auto">
        {orders.slice(0, 15).map((order) => (
          <li key={order.id} className="flex items-center justify-between gap-2 rounded-lg bg-app/40 px-2.5 py-1.5 text-xs">
            <span className="truncate text-app">
              {order.orderNumber} · {formatDateTime(order.createdAt)}
            </span>
            <span className="shrink-0 font-semibold text-app">{formatCurrency(order.totalAmount)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Bistrodesk Phase 1/7 (requirement #2) gave `Order` a real `customer` FK, and `/orders/history`
 * now accepts a `customerId` filter (see OrderController#history) - that's the authoritative,
 * server-side, branch-aware, uncapped source, and is tried first. It only ever comes back empty
 * for a customer whose past orders predate the FK (never populated) or who genuinely has none, and
 * those two cases can't be told apart from here - so when it's empty this falls back to the old
 * best-effort scan of the general history feed (matched by phone first, then case-insensitive name),
 * which is the only way to surface those pre-FK orders at all. Both queries are fetched lazily only
 * while a customer's detail/edit modal is open, and degrade quietly if the signed-in user lacks the
 * separate order-history permission (403) rather than surfacing a hard error on a nice-to-have panel. */
function OrderHistorySection({ customer, enabled }: { customer: CustomerDto; enabled: boolean }) {
  const primaryQuery = useQuery({
    queryKey: ['orders', 'history', 'by-customer', customer.id],
    queryFn: () => api.get<OrderDto[]>('/orders/history', { customerId: customer.id, limit: 200 }),
    enabled,
    retry: false,
    staleTime: 30_000,
  })

  const primaryEmpty = primaryQuery.isSuccess && primaryQuery.data.length === 0

  const legacyQuery = useQuery({
    queryKey: ['orders', 'history', 'customer-lookup'],
    queryFn: () => api.get<OrderDto[]>('/orders/history', { limit: 200 }),
    enabled: enabled && primaryEmpty,
    retry: false,
    staleTime: 30_000,
  })

  if (!enabled) return null

  if (primaryQuery.isLoading) {
    return (
      <div className="flex items-center gap-2 text-xs text-muted">
        <Spinner className="h-3.5 w-3.5" /> Checking order history…
      </div>
    )
  }

  if (primaryQuery.isError) {
    const err = primaryQuery.error
    if (err instanceof ApiError && err.status === 403) {
      return <p className="text-xs text-muted">You don't have permission to view order history.</p>
    }
    // A nice-to-have lookup failing for any other reason shouldn't clutter an otherwise-working modal.
    return null
  }

  const primaryOrders = primaryQuery.data ?? []
  if (primaryOrders.length > 0) {
    return <OrderList orders={primaryOrders} note="Linked to this customer's record." />
  }

  // Authoritative source came back empty - fall back to the pre-Phase-7 best-effort scan.
  if (legacyQuery.isLoading) {
    return (
      <div className="flex items-center gap-2 text-xs text-muted">
        <Spinner className="h-3.5 w-3.5" /> Checking older order history…
      </div>
    )
  }

  if (legacyQuery.isError) {
    return <p className="text-xs text-muted">No past orders found for this customer.</p>
  }

  const orders = legacyQuery.data ?? []
  const phone = customer.phone?.trim()
  const name = customer.name.trim().toLowerCase()
  const byPhone = phone ? orders.filter((o) => (o.customerPhone ?? '').trim() === phone) : []
  const matches = byPhone.length > 0 ? byPhone : orders.filter((o) => (o.customerName ?? '').trim().toLowerCase() === name)
  const matchedByPhone = byPhone.length > 0

  if (matches.length === 0) {
    return <p className="text-xs text-muted">No past orders found for this customer in the last 200 orders.</p>
  }

  return (
    <OrderList
      orders={matches}
      note={
        matchedByPhone
          ? 'Matched by phone number (predates this customer\'s linked record).'
          : "Matched by customer name only - names can collide, so treat this as best-effort."
      }
    />
  )
}

interface CustomerModalProps {
  open: boolean
  onClose: () => void
  customer: CustomerDto | null
  canManage: boolean
  branches: AccessibleBranchDto[]
}

/** Single modal covering all three cases the page needs: registering a new customer (customer=null),
 * editing one (canManage=true), and read-only detail (canManage=false) - same form markup throughout
 * so the layout stays consistent, just disabled and stripped of the Save action when read-only.
 *
 * <p>Bistrodesk branch-isolation release (requirement #6): a customer now belongs to exactly one
 * branch, resolved server-side (a single-branch caller or one with a default branch never needs to
 * choose) - the branch picker below only renders when {@code branches} has more than one entry,
 * i.e. only for a multi-branch/unrestricted caller who genuinely has a choice to make. */
function CustomerModal({ open, onClose, customer, canManage, branches }: CustomerModalProps) {
  const queryClient = useQueryClient()
  const isCreate = !customer
  const readOnly = !isCreate && !canManage
  const showBranchPicker = isCreate && branches.length > 1

  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [notes, setNotes] = useState('')
  const [branchId, setBranchId] = useState('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setName(customer?.name ?? '')
    setPhone(customer?.phone ?? '')
    setEmail(customer?.email ?? '')
    setNotes(customer?.notes ?? '')
    setBranchId('')
    setError(null)
  }, [open, customer])

  const invalidateAndClose = () => {
    // Broad invalidation on purpose - a create/edit should refresh every search-scoped
    // ['customers', <query>] cache entry, not just whichever one happened to be active.
    queryClient.invalidateQueries({ queryKey: ['customers'] })
    onClose()
  }

  const describeError = (err: unknown, fallback: string) => {
    if (err instanceof ApiError) {
      if (err.status === 409) {
        return 'This customer was updated elsewhere since you opened this form. Close and reopen it to see the latest details before trying again.'
      }
      return err.message || fallback
    }
    return fallback
  }

  const createCustomer = useMutation({
    mutationFn: () =>
      api.post<CustomerDto>('/customers', {
        name: name.trim(),
        phone: phone.trim() || null,
        email: email.trim() || null,
        notes: notes.trim() || null,
        branchId: branchId || null,
      } satisfies CreateCustomerRequest),
    onSuccess: invalidateAndClose,
    onError: (err) => setError(describeError(err, 'Could not register this customer')),
  })

  const updateCustomer = useMutation({
    mutationFn: () =>
      api.patch<CustomerDto>(`/customers/${customer!.id}`, {
        name: name.trim(),
        phone: phone.trim() || null,
        email: email.trim() || null,
        notes: notes.trim() || null,
        version: customer!.version,
      } satisfies UpdateCustomerRequest),
    onSuccess: invalidateAndClose,
    onError: (err) => setError(describeError(err, 'Could not save changes to this customer')),
  })

  const saving = createCustomer.isPending || updateCustomer.isPending
  const tag = customer ? getCustomerTag(customer) : null

  if (!open) return null

  return (
    <Modal open={open} onClose={onClose} title={isCreate ? 'Register New Customer' : customer!.name} widthClassName="max-w-md">
      <div className="space-y-5">
        {customer && (
          <div>
            {tag && (
              <Badge tone={tag.tone} className="mb-2">
                {tag.label}
              </Badge>
            )}
            <div className="grid grid-cols-3 gap-2 rounded-xl bg-app/50 px-3 py-2.5 text-xs">
              <div>
                <div className="font-semibold uppercase tracking-wide text-muted">Visits</div>
                <div className="mt-0.5 text-sm font-bold text-app">{customer.visitCount}</div>
              </div>
              <div>
                <div className="font-semibold uppercase tracking-wide text-muted">Total Spend</div>
                <div className="mt-0.5 text-sm font-bold text-app">{formatCurrency(customer.totalSpend)}</div>
              </div>
              <div>
                <div className="font-semibold uppercase tracking-wide text-muted">Last Visit</div>
                <div className="mt-0.5 text-sm font-bold text-app">
                  {customer.lastVisitAt ? formatDateTime(customer.lastVisitAt) : 'Never'}
                </div>
              </div>
            </div>
          </div>
        )}

        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault()
            if (readOnly) return
            setError(null)
            if (isCreate) createCustomer.mutate()
            else updateCustomer.mutate()
          }}
        >
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Name</label>
            <input
              required
              disabled={readOnly}
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Priya Sharma"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500 disabled:opacity-70"
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
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Phone</label>
            <input
              disabled={readOnly}
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="e.g. 98765 43210"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500 disabled:opacity-70"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Email</label>
            <input
              type="email"
              disabled={readOnly}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="e.g. priya@example.com"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500 disabled:opacity-70"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Notes</label>
            <textarea
              disabled={readOnly}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              placeholder="e.g. prefers window seating, allergic to peanuts"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500 disabled:opacity-70"
            />
          </div>

          {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

          {readOnly ? (
            <p className="rounded-lg bg-app/50 px-3 py-2 text-xs text-muted">
              You have view-only access to customers. Ask a manager for edit permission to update these details.
            </p>
          ) : (
            <Button type="submit" className="w-full" disabled={saving}>
              <UserPlus className="h-4 w-4" />
              {saving ? 'Saving…' : isCreate ? 'Register Customer' : 'Save Changes'}
            </Button>
          )}
        </form>

        {customer && (
          <div className="border-t border-app pt-4">
            <OrderHistorySection customer={customer} enabled={open} />
          </div>
        )}
      </div>
    </Modal>
  )
}

function CustomerCard({ customer, onOpen }: { customer: CustomerDto; onOpen: () => void }) {
  const tag = getCustomerTag(customer)

  return (
    <button type="button" onClick={onOpen} className="block w-full text-left">
      <Card className="h-full p-4 transition-colors hover:border-brand-300 dark:hover:border-brand-700">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="truncate text-sm font-bold text-app">{customer.name}</div>
            <div className="mt-1 flex items-center gap-1.5 text-xs text-muted">
              <Phone className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">{customer.phone || 'No phone on file'}</span>
            </div>
            {customer.email && (
              <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted">
                <Mail className="h-3.5 w-3.5 shrink-0" />
                <span className="truncate">{customer.email}</span>
              </div>
            )}
          </div>
          {tag && (
            <Badge tone={tag.tone} className="shrink-0">
              {tag.label}
            </Badge>
          )}
        </div>

        <div className="mt-3 grid grid-cols-3 gap-2 rounded-xl bg-app/50 px-2.5 py-2 text-center">
          <div>
            <div className="text-sm font-extrabold text-app">{customer.visitCount}</div>
            <div className="text-[10px] font-semibold uppercase tracking-wide text-muted">Visits</div>
          </div>
          <div>
            <div className="truncate text-sm font-extrabold text-app">{formatCurrency(customer.totalSpend)}</div>
            <div className="text-[10px] font-semibold uppercase tracking-wide text-muted">Spend</div>
          </div>
          <div>
            <div className="truncate text-sm font-extrabold text-app">
              {customer.lastVisitAt ? formatDateTime(customer.lastVisitAt) : '—'}
            </div>
            <div className="text-[10px] font-semibold uppercase tracking-wide text-muted">Last Visit</div>
          </div>
        </div>
      </Card>
    </button>
  )
}

export function CustomersPage() {
  const { hasPermission, terminal, defaultBranchId } = useAuthStore()
  const canManage = hasPermission('CUSTOMER_MANAGE')

  const [search, setSearch] = useState('')
  const [modalCustomer, setModalCustomer] = useState<CustomerDto | null>(null)
  const [createOpen, setCreateOpen] = useState(false)

  // Bistrodesk post-release fix: this never passed a branchId at all, so any unrestricted account
  // (Owner/Admin with no branch assignments, or VIEW_ALL_BRANCHES) always saw every branch's
  // customers merged together, regardless of terminal - confirmed real-world bug ("a customer
  // created in branch A is visible from other branches"). Defaults to this terminal's own bound
  // branch, same convention as Dashboard/Orders Log, with the same Branch Switcher for a legitimate
  // cross-branch lookup (e.g. an Owner tracing a guest who has visited more than one location).
  const [branchId, setBranchId] = useState<string | null>(terminal?.branchId ?? defaultBranchId ?? null)

  const customersQuery = useQuery({
    queryKey: ['customers', search, branchId],
    queryFn: () => api.get<CustomerDto[]>('/customers', { query: search.trim() || undefined, branchId: branchId ?? undefined }),
  })

  // Bistrodesk branch-isolation release (requirement #6): only fetched to decide whether the
  // create form's branch picker should render at all - a single-branch/already-scoped caller
  // never sees more than one entry here, so the picker stays hidden and the server-side default
  // resolution (this caller's own branch) is all that's ever needed.
  const branchesQuery = useQuery({
    queryKey: ['branches', 'accessible'],
    queryFn: () => api.get<AccessibleBranchDto[]>('/branches/accessible'),
  })
  const branches = branchesQuery.data ?? []

  const customers = customersQuery.data ?? []
  const modalOpen = createOpen || !!modalCustomer

  const closeModal = () => {
    setCreateOpen(false)
    setModalCustomer(null)
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold text-app">
            <Users className="h-5 w-5 text-brand-600" /> Customer Directory
          </h2>
          <p className="mt-0.5 text-sm text-muted">
            {canManage ? 'Look up, register, and update customer records.' : 'Look up customer records.'}
          </p>
        </div>
        {canManage && (
          <Button onClick={() => setCreateOpen(true)}>
            <UserPlus className="h-4 w-4" /> Register Customer
          </Button>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <div className="relative max-w-sm flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by name or phone…"
            className="w-full rounded-lg border border-app bg-surface py-2 pl-9 pr-3 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <BranchSwitcher value={branchId} onChange={setBranchId} />
        <Button size="sm" variant="secondary" onClick={() => customersQuery.refetch()}>
          <RefreshCw className={cn('h-3.5 w-3.5', customersQuery.isFetching && 'animate-spin')} /> Refresh
        </Button>
      </div>

      {customersQuery.isLoading ? (
        <FullPageSpinner label="Loading customers…" />
      ) : customersQuery.isError ? (
        <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
          {customersQuery.error instanceof ApiError ? customersQuery.error.message : 'Could not load customers'}
        </div>
      ) : customers.length === 0 ? (
        <div className="flex h-64 flex-col items-center justify-center gap-2 text-muted">
          {search.trim() ? <Search className="h-8 w-8" /> : <Sparkles className="h-8 w-8" />}
          <span className="text-sm font-medium">
            {search.trim() ? 'No customers match that search' : 'No customers registered yet'}
          </span>
          {canManage && !search.trim() && (
            <Button size="sm" variant="secondary" onClick={() => setCreateOpen(true)}>
              <UserPlus className="h-3.5 w-3.5" /> Register the first customer
            </Button>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {customers.map((customer) => (
            <CustomerCard key={customer.id} customer={customer} onOpen={() => setModalCustomer(customer)} />
          ))}
        </div>
      )}

      {customers.length > 0 && (
        <p className="flex items-center gap-1 text-xs text-muted">
          <Star className="h-3.5 w-3.5" /> {customers.length} customer{customers.length === 1 ? '' : 's'}
          {search.trim() && ' matching your search'}
        </p>
      )}

      <CustomerModal
        open={modalOpen}
        onClose={closeModal}
        customer={createOpen ? null : modalCustomer}
        canManage={canManage}
        branches={branches}
      />
    </div>
  )
}
