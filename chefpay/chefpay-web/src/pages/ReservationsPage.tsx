import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  CalendarClock,
  CalendarPlus,
  CalendarX2,
  Check,
  ChevronRight,
  History,
  Pencil,
  Phone,
  PlayCircle,
  Plus,
  RefreshCw,
  StickyNote,
  Users,
  UserX,
  XCircle,
} from 'lucide-react'
import { useMemo, useState } from 'react'

import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { FeatureLockedScreen } from '@/components/ui/FeatureLockedScreen'
import { Modal } from '@/components/ui/Modal'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useEntitlements } from '@/hooks/useEntitlements'
import { api } from '@/lib/api'
import { cn, formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import { ApiError } from '@/types/api'
import type {
  AreaDto,
  CreateReservationRequest,
  ReservationDto,
  ReservationStatus,
  TableDto,
  UpdateReservationRequest,
} from '@/types/api'

// ---- Local status presentation config - the backend only ever sends these five values (see
// ReservationStatus in types/api.ts / com.chefpay.server.reservations). ----

const STATUS_TONE: Record<ReservationStatus, 'neutral' | 'info' | 'warning' | 'success' | 'danger' | 'brand'> = {
  PENDING: 'warning',
  CONFIRMED: 'info',
  SEATED: 'success',
  CANCELLED: 'danger',
  NO_SHOW: 'neutral',
}

const STATUS_LABEL: Record<ReservationStatus, string> = {
  PENDING: 'Pending',
  CONFIRMED: 'Confirmed',
  SEATED: 'Seated',
  CANCELLED: 'Cancelled',
  NO_SHOW: 'No-show',
}

const FINAL_STATUSES = new Set<ReservationStatus>(['SEATED', 'CANCELLED', 'NO_SHOW'])

function friendlyError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 409 || err.errorCode === 'VERSION_CONFLICT') {
      return 'This reservation was changed elsewhere - refresh and try again.'
    }
    return err.message || fallback
  }
  return fallback
}

/** Converts a `<input type="datetime-local">` value (no timezone, e.g. "2026-08-28T19:00") into a
 * full ISO datetime string the backend's LocalDateTime deserializer accepts. */
function toIsoDateTime(localValue: string): string {
  return localValue.length === 16 ? `${localValue}:00` : localValue
}

/** Inverse of toIsoDateTime, for pre-filling the edit form's datetime-local input from a
 * server-supplied ISO/LocalDateTime string (which has no trailing "Z" / offset). */
function toLocalInputValue(iso: string): string {
  return iso.length >= 16 ? iso.slice(0, 16) : iso
}

function dateGroupLabel(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const today = new Date()
  const isSameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString()
  const tomorrow = new Date(today)
  tomorrow.setDate(today.getDate() + 1)
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  if (isSameDay(d, today)) return 'Today'
  if (isSameDay(d, tomorrow)) return 'Tomorrow'
  if (isSameDay(d, yesterday)) return 'Yesterday'
  return d.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
}

function timeOf(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
}

// ---- Create / Edit modal - shared form for both flows, distinguished by whether `editing` is set. ----

function ReservationFormModal({
  open,
  onClose,
  editing,
  tables,
  areas,
}: {
  open: boolean
  onClose: () => void
  editing: ReservationDto | null
  tables: TableDto[]
  areas: AreaDto[]
}) {
  const queryClient = useQueryClient()
  // Bistrodesk Phase 2: same source ReservationsPage's sibling PosTerminalPage already uses for
  // orders - lets a multi-branch install's table-less reservations resolve a branch automatically
  // from the terminal/user's own configured branch, with zero UI change for a single-branch
  // install (both are undefined there, and the server falls back to "the one branch").
  const { terminal, defaultBranchId } = useAuthStore()
  const reservationBranchId = terminal?.branchId ?? defaultBranchId ?? undefined
  const [customerName, setCustomerName] = useState(editing?.customerName ?? '')
  const [customerPhone, setCustomerPhone] = useState(editing?.customerPhone ?? '')
  const [partySize, setPartySize] = useState(editing?.partySize ?? 2)
  const [reservedFor, setReservedFor] = useState(editing ? toLocalInputValue(editing.reservedFor) : '')
  const [tableId, setTableId] = useState(editing?.tableId ?? '')
  // Bistrodesk Phase 8 (requirement #24): only meaningful while no table is chosen yet - a host
  // picking a specific table already answers "does this fit" more precisely than an area does.
  const [areaName, setAreaName] = useState('')
  // Bistrodesk Phase 8 (requirement #25): blank means "use the restaurant's configured default".
  // Prefilled from durationOverrideMinutes (the raw override), NOT the resolved durationMinutes -
  // using the resolved value here would silently pin every edited reservation to an explicit
  // override the moment it's saved, even if this field is never touched (see ReservationDto's
  // javadoc server-side). Kept as free text so clearing it is just clearing the field, no separate
  // "reset to default" affordance needed.
  const [durationMinutes, setDurationMinutes] = useState(
    editing?.durationOverrideMinutes ? String(editing.durationOverrideMinutes) : '',
  )
  const [notes, setNotes] = useState(editing?.notes ?? '')
  const [error, setError] = useState<string | null>(null)

  const reset = () => {
    setCustomerName('')
    setCustomerPhone('')
    setPartySize(2)
    setReservedFor('')
    setTableId('')
    setAreaName('')
    setDurationMinutes('')
    setNotes('')
    setError(null)
  }

  const invalidateAll = () => queryClient.invalidateQueries({ queryKey: ['reservations'] })
  const trimmedDuration = durationMinutes.trim()
  const parsedDuration = trimmedDuration && !Number.isNaN(Number(trimmedDuration)) ? Number(trimmedDuration) : null

  const createReservation = useMutation({
    mutationFn: () =>
      api.post<ReservationDto>('/reservations', {
        customerName,
        customerPhone: customerPhone || null,
        partySize,
        reservedFor: toIsoDateTime(reservedFor),
        tableId: tableId || null,
        branchId: tableId ? null : reservationBranchId ?? null,
        areaName: tableId ? null : areaName || null,
        durationMinutes: parsedDuration,
        notes: notes || null,
      } satisfies CreateReservationRequest),
    onSuccess: () => {
      invalidateAll()
      reset()
      onClose()
    },
    onError: (err) => setError(friendlyError(err, 'Could not create this reservation')),
  })

  const updateReservation = useMutation({
    mutationFn: () => {
      if (!editing) throw new Error('no reservation selected')
      const body: UpdateReservationRequest = {
        customerName,
        customerPhone: customerPhone || null,
        partySize,
        reservedFor: toIsoDateTime(reservedFor),
        durationMinutes: parsedDuration,
        notes: notes || null,
        version: editing.version,
      }
      if (tableId) {
        body.tableId = tableId
      } else {
        if (editing.tableId) {
          body.clearTable = true
        }
        // Bistrodesk Phase 2: staying (or becoming) table-less - carry the resolved branch along
        // so a multi-branch install's edit doesn't drop/omit it once there's no table to infer it
        // from. No-op for a single-branch install (server already has "the one branch" fallback).
        if (!editing.branchId && reservationBranchId) {
          body.branchId = reservationBranchId
        }
      }
      return api.patch<ReservationDto>(`/reservations/${editing.id}`, body)
    },
    onSuccess: () => {
      invalidateAll()
      onClose()
    },
    onError: (err) => setError(friendlyError(err, 'Could not save changes')),
  })

  const pending = createReservation.isPending || updateReservation.isPending

  return (
    <Modal
      open={open}
      onClose={() => {
        reset()
        onClose()
      }}
      title={editing ? `Edit reservation - ${editing.customerName}` : 'New Reservation'}
      widthClassName="max-w-md"
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          if (editing) updateReservation.mutate()
          else createReservation.mutate()
        }}
      >
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Customer name</label>
          <input
            required
            value={customerName}
            onChange={(e) => setCustomerName(e.target.value)}
            placeholder="e.g. Priya Sharma"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Phone (optional)</label>
            <input
              type="tel"
              value={customerPhone}
              onChange={(e) => setCustomerPhone(e.target.value)}
              placeholder="e.g. 98765 43210"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Party size</label>
            <input
              type="number"
              min={1}
              required
              value={partySize}
              onChange={(e) => setPartySize(Number(e.target.value))}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Date &amp; time</label>
            <input
              type="datetime-local"
              required
              value={reservedFor}
              onChange={(e) => setReservedFor(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">
              Duration (min){editing ? '' : ', optional'}
            </label>
            <input
              type="number"
              min={1}
              placeholder={String(editing?.durationMinutes ?? 90)}
              value={durationMinutes}
              onChange={(e) => setDurationMinutes(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
        </div>

        {tables.length > 0 && (
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Assign table (optional)</label>
            <select
              value={tableId}
              onChange={(e) => setTableId(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            >
              <option value="">No table assigned yet</option>
              {tables.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name} (seats {t.seatingCapacity})
                </option>
              ))}
            </select>
          </div>
        )}

        {/* Bistrodesk Phase 8 (requirement #24): only useful while no specific table has been
            picked yet - it's how a host who just knows "seat them in the Patio" gets a real
            capacity check without having to pick an exact table first. Hidden once a table is
            chosen (that table's own seating already answers the capacity question directly), and
            hidden entirely for an install with no Areas configured at all. */}
        {areas.length > 0 && !tableId && (
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Seating area (optional)</label>
            <select
              value={areaName}
              onChange={(e) => setAreaName(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            >
              <option value="">No area preference</option>
              {areas.map((a) => (
                <option key={a.id} value={a.name}>
                  {a.name}
                </option>
              ))}
            </select>
          </div>
        )}

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Notes (optional)</label>
          <textarea
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            rows={2}
            placeholder="e.g. window seat, allergy, birthday"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>

        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

        <Button type="submit" className="w-full" disabled={pending}>
          {editing ? (
            <>
              <Pencil className="h-4 w-4" /> {pending ? 'Saving…' : 'Save changes'}
            </>
          ) : (
            <>
              <CalendarPlus className="h-4 w-4" /> {pending ? 'Booking…' : 'Book reservation'}
            </>
          )}
        </Button>
      </form>
    </Modal>
  )
}

// ---- Cancel confirmation - a soft-delete via PATCH status:CANCELLED, no hard-delete endpoint exists. ----

function CancelModal({ reservation, onClose, onDone }: { reservation: ReservationDto; onClose: () => void; onDone: () => void }) {
  const [error, setError] = useState<string | null>(null)

  const cancel = useMutation({
    mutationFn: () =>
      api.patch<ReservationDto>(`/reservations/${reservation.id}`, {
        status: 'CANCELLED',
        version: reservation.version,
      } satisfies UpdateReservationRequest),
    onSuccess: onDone,
    onError: (err) => setError(friendlyError(err, 'Could not cancel this reservation')),
  })

  return (
    <Modal open onClose={onClose} title={`Cancel reservation for ${reservation.customerName}?`} widthClassName="max-w-sm">
      <div className="space-y-4">
        <p className="text-sm text-muted">
          This marks the {formatDateTime(reservation.reservedFor)} booking for {reservation.partySize} as cancelled. It stays
          visible in the "All / History" tab.
        </p>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" type="button" onClick={onClose}>
            Keep reservation
          </Button>
          <Button variant="danger" className="flex-1" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
            <CalendarX2 className="h-4 w-4" /> {cancel.isPending ? 'Cancelling…' : 'Cancel it'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// ---- One reservation's card, with quick status-transition buttons so most updates never need the
// edit modal at all. ----

function ReservationCard({
  reservation,
  canManage,
  onEdit,
  onCancel,
  onSetStatus,
  settingStatusId,
}: {
  reservation: ReservationDto
  canManage: boolean
  onEdit: () => void
  onCancel: () => void
  onSetStatus: (r: ReservationDto, status: ReservationStatus) => void
  settingStatusId: string | null
}) {
  const isFinal = FINAL_STATUSES.has(reservation.status)
  const busy = settingStatusId === reservation.id

  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-sm font-bold text-app">{reservation.customerName}</span>
            <Badge tone={STATUS_TONE[reservation.status]}>{STATUS_LABEL[reservation.status]}</Badge>
          </div>
          <div className="mt-1 flex items-center gap-1 text-xs text-muted">
            <CalendarClock className="h-3.5 w-3.5" /> {timeOf(reservation.reservedFor)} – {timeOf(reservation.reservedUntil)}
          </div>
        </div>
        <div className="flex items-center gap-1 text-sm font-bold text-app">
          <Users className="h-4 w-4 text-muted" /> {reservation.partySize}
        </div>
      </div>

      <div className="mt-3 grid grid-cols-2 gap-2 rounded-xl bg-app/50 px-3 py-2 text-xs">
        <div>
          <div className="font-semibold uppercase tracking-wide text-muted">Phone</div>
          <div className="mt-0.5 flex items-center gap-1 font-medium text-app">
            <Phone className="h-3 w-3 text-muted" /> {reservation.customerPhone ?? '—'}
          </div>
        </div>
        <div>
          <div className="font-semibold uppercase tracking-wide text-muted">Table</div>
          <div className="mt-0.5 font-medium text-app">{reservation.tableName ?? 'Unassigned'}</div>
        </div>
      </div>

      {reservation.notes && (
        <div className="mt-2 flex items-start gap-1.5 rounded-lg bg-app/30 px-2.5 py-1.5 text-xs text-muted">
          <StickyNote className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span className="text-app">{reservation.notes}</span>
        </div>
      )}

      {canManage && (
        <div className="mt-3 flex flex-wrap items-center gap-1.5 border-t border-app pt-2.5">
          {reservation.status === 'PENDING' && (
            <button
              type="button"
              disabled={busy}
              onClick={() => onSetStatus(reservation, 'CONFIRMED')}
              className="flex items-center gap-1 rounded-lg bg-info-soft px-2.5 py-1.5 text-xs font-bold text-info hover:opacity-80 disabled:opacity-50"
            >
              <Check className="h-3.5 w-3.5" /> Confirm
            </button>
          )}
          {(reservation.status === 'PENDING' || reservation.status === 'CONFIRMED') && (
            <button
              type="button"
              disabled={busy}
              onClick={() => onSetStatus(reservation, 'SEATED')}
              className="flex items-center gap-1 rounded-lg bg-success-soft px-2.5 py-1.5 text-xs font-bold text-success hover:opacity-80 disabled:opacity-50"
            >
              <PlayCircle className="h-3.5 w-3.5" /> Seat
            </button>
          )}
          {(reservation.status === 'PENDING' || reservation.status === 'CONFIRMED') && (
            <button
              type="button"
              disabled={busy}
              onClick={() => onSetStatus(reservation, 'NO_SHOW')}
              className="flex items-center gap-1 rounded-lg bg-app px-2.5 py-1.5 text-xs font-bold text-muted hover:bg-app/70 disabled:opacity-50"
            >
              <UserX className="h-3.5 w-3.5" /> No-show
            </button>
          )}
          <button
            type="button"
            title="Edit details"
            onClick={onEdit}
            className="ml-auto rounded-lg p-1.5 text-muted hover:bg-app"
          >
            <Pencil className="h-4 w-4" />
          </button>
          {!isFinal && (
            <button type="button" title="Cancel reservation" onClick={onCancel} className="rounded-lg p-1.5 text-muted hover:bg-danger-soft hover:text-danger">
              <XCircle className="h-4 w-4" />
            </button>
          )}
        </div>
      )}
    </Card>
  )
}

export function ReservationsPage() {
  const queryClient = useQueryClient()
  const { hasPermission, terminal, defaultBranchId } = useAuthStore()
  const { isEnabled: isFeatureEnabled, isLoading: entitlementsLoading } = useEntitlements()
  const canManage = hasPermission('RESERVATION_MANAGE')
  // Bistrodesk Phase 8 (requirement #24): same branch resolution ReservationFormModal uses for the
  // create/update calls themselves - so the Areas dropdown offered here is scoped to the same
  // branch a table-less booking would actually be checked against.
  const reservationBranchId = terminal?.branchId ?? defaultBranchId ?? undefined

  const [tab, setTab] = useState<'upcoming' | 'all'>('upcoming')
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<ReservationDto | null>(null)
  const [cancelTarget, setCancelTarget] = useState<ReservationDto | null>(null)
  const [statusError, setStatusError] = useState<string | null>(null)
  const [settingStatusId, setSettingStatusId] = useState<string | null>(null)

  const upcomingOnly = tab === 'upcoming'

  const reservationsQuery = useQuery({
    queryKey: ['reservations', upcomingOnly],
    queryFn: () => api.get<ReservationDto[]>('/reservations', { upcomingOnly }),
    refetchInterval: 30_000,
  })

  // Optional table-assignment dropdown - a user with RESERVATION_MANAGE but not TABLE_VIEW simply
  // won't see it, per the task's "degrade gracefully" guidance rather than crashing the page.
  const tablesQuery = useQuery({
    queryKey: ['tables', 'for-reservations'],
    queryFn: () => api.get<TableDto[]>('/tables'),
    retry: false,
    throwOnError: false,
  })

  // Bistrodesk Phase 8 (requirement #24): optional Areas dropdown, same "degrade gracefully if the
  // caller lacks the underlying permission" behavior as tablesQuery above - an install with no
  // Areas configured (or a caller who can't list them) just never sees the field.
  const areasQuery = useQuery({
    queryKey: ['areas', 'for-reservations', reservationBranchId],
    queryFn: () => api.get<AreaDto[]>('/areas', reservationBranchId ? { branchId: reservationBranchId } : undefined),
    retry: false,
    throwOnError: false,
  })

  const setStatus = useMutation({
    mutationFn: ({ reservation, status }: { reservation: ReservationDto; status: ReservationStatus }) =>
      api.patch<ReservationDto>(`/reservations/${reservation.id}`, {
        status,
        version: reservation.version,
      } satisfies UpdateReservationRequest),
    onMutate: ({ reservation }) => {
      setStatusError(null)
      setSettingStatusId(reservation.id)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['reservations'] }),
    onError: (err) => setStatusError(friendlyError(err, 'Could not update this reservation')),
    onSettled: () => setSettingStatusId(null),
  })

  const reservations = reservationsQuery.data ?? []
  const tables = tablesQuery.data ?? []
  const areas = areasQuery.data ?? []

  const grouped = useMemo(() => {
    const map = new Map<string, ReservationDto[]>()
    for (const r of reservations) {
      const key = dateGroupLabel(r.reservedFor)
      if (!map.has(key)) map.set(key, [])
      map.get(key)!.push(r)
    }
    return [...map.entries()]
  }, [reservations])

  const counts = useMemo(() => {
    const c: Record<ReservationStatus, number> = { PENDING: 0, CONFIRMED: 0, SEATED: 0, CANCELLED: 0, NO_SHOW: 0 }
    for (const r of reservations) c[r.status]++
    return c
  }, [reservations])

  // Bistrodesk branch-isolation release (requirement #5): the server already enforces RESERVATIONS
  // via ReservationController's class-level @RequiresFeature - this is the matching client-side
  // lock, same pattern as InventoryPage/PurchaseOrdersPage.
  if (entitlementsLoading) return <FullPageSpinner label="Loading…" />

  if (!isFeatureEnabled('RESERVATIONS')) {
    return (
      <FeatureLockedScreen
        title="Not Included in Your Plan"
        message="Reservations aren't part of this branch's current subscription plan. Contact Bistrodesk support to upgrade."
      />
    )
  }

  if (reservationsQuery.isLoading) return <FullPageSpinner label="Loading reservations…" />

  return (
    <div className="space-y-6">
      <div>
        <h2 className="flex items-center gap-2 text-lg font-bold text-app">
          <CalendarClock className="h-5 w-5 text-brand-600" /> Reservations
        </h2>
        <p className="mt-0.5 text-sm text-muted">Manage phone and walk-in bookings, independent of the live floor plan.</p>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-app bg-surface px-4 py-3">
        <div className="flex flex-wrap items-center gap-4 text-xs font-semibold">
          {(Object.keys(STATUS_LABEL) as ReservationStatus[]).map((s) => (
            <div key={s} className="flex items-center gap-1.5">
              <Badge tone={STATUS_TONE[s]}>{counts[s]}</Badge>
              <span className="text-app">{STATUS_LABEL[s]}</span>
            </div>
          ))}
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={() => reservationsQuery.refetch()}>
            <RefreshCw className={cn('h-3.5 w-3.5', reservationsQuery.isFetching && 'animate-spin')} /> Refresh
          </Button>
          {canManage && (
            <Button
              size="sm"
              onClick={() => {
                setEditing(null)
                setFormOpen(true)
              }}
            >
              <Plus className="h-3.5 w-3.5" /> New Reservation
            </Button>
          )}
        </div>
      </div>

      <div className="flex gap-1 rounded-xl border border-app bg-surface p-1 text-sm font-semibold w-fit">
        <button
          type="button"
          onClick={() => setTab('upcoming')}
          className={cn(
            'flex items-center gap-1.5 rounded-lg px-3 py-1.5 transition-colors',
            tab === 'upcoming' ? 'bg-brand-600 text-white' : 'text-muted hover:bg-app',
          )}
        >
          <CalendarClock className="h-3.5 w-3.5" /> Upcoming
        </button>
        <button
          type="button"
          onClick={() => setTab('all')}
          className={cn(
            'flex items-center gap-1.5 rounded-lg px-3 py-1.5 transition-colors',
            tab === 'all' ? 'bg-brand-600 text-white' : 'text-muted hover:bg-app',
          )}
        >
          <History className="h-3.5 w-3.5" /> All / History
        </button>
      </div>

      {statusError && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{statusError}</div>}

      {reservations.length === 0 ? (
        <div className="flex h-64 flex-col items-center justify-center gap-2 text-muted">
          <CalendarClock className="h-8 w-8" />
          <span className="text-sm font-medium">
            {tab === 'upcoming' ? 'No upcoming reservations' : 'No reservations recorded yet'}
          </span>
          {canManage && tab === 'upcoming' && (
            <Button
              size="sm"
              variant="secondary"
              className="mt-2"
              onClick={() => {
                setEditing(null)
                setFormOpen(true)
              }}
            >
              <Plus className="h-3.5 w-3.5" /> Book the first one
            </Button>
          )}
        </div>
      ) : (
        <div className="space-y-6">
          {grouped.map(([label, group]) => (
            <div key={label}>
              <h3 className="mb-2 flex items-center gap-1 text-sm font-bold text-app">
                {label} <ChevronRight className="h-3.5 w-3.5 text-muted" /> {group.length} booking{group.length === 1 ? '' : 's'}
              </h3>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
                {group.map((r) => (
                  <ReservationCard
                    key={r.id}
                    reservation={r}
                    canManage={canManage}
                    onEdit={() => {
                      setEditing(r)
                      setFormOpen(true)
                    }}
                    onCancel={() => setCancelTarget(r)}
                    onSetStatus={(reservation, status) => setStatus.mutate({ reservation, status })}
                    settingStatusId={settingStatusId}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {canManage && (
        <ReservationFormModal
          // Remount on every open (and per reservation) - the form's fields are seeded from
          // `editing` only in useState's initializer, so without a fresh key here switching from
          // "New" to "Edit <X>" (or between two different reservations) would keep showing
          // whatever fields were present the first time this modal ever mounted.
          key={formOpen ? (editing?.id ?? 'new') : 'closed'}
          open={formOpen}
          onClose={() => {
            setFormOpen(false)
            setEditing(null)
          }}
          editing={editing}
          tables={tables}
          areas={areas}
        />
      )}

      {canManage && cancelTarget && (
        <CancelModal
          reservation={cancelTarget}
          onClose={() => setCancelTarget(null)}
          onDone={() => {
            setCancelTarget(null)
            queryClient.invalidateQueries({ queryKey: ['reservations'] })
          }}
        />
      )}
    </div>
  )
}
