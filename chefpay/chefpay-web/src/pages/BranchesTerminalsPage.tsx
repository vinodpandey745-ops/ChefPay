import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Building2,
  CheckCircle2,
  Loader2,
  MonitorSmartphone,
  Pencil,
  Plus,
  RefreshCw,
  ShieldAlert,
  Trash2,
  Users,
  XCircle,
} from 'lucide-react'
import { useState } from 'react'

import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/Card'
import { Modal } from '@/components/ui/Modal'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { api } from '@/lib/api'
import { cn, formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import { ApiError } from '@/types/api'
import type {
  BranchDto,
  BulkCreateTerminalsRequest,
  CreateTerminalRequest,
  RestaurantDto,
  TerminalDto,
  UpdateBranchRequest,
  UpdateTerminalRequest,
} from '@/types/api'

/** Same 409-optimistic-locking-conflict message convention as SettingsPage's save mutation and
 * CustomersPage's create/update mutations - the terminal PUT endpoint never returns a business-
 * rule 409, only a version conflict, so a blanket status check is safe here specifically. */
function describeTerminalError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 409) {
      return 'Someone else changed this terminal while you were editing. Refresh the page and try again.'
    }
    return err.message || fallback
  }
  return fallback
}

/** Round 20's fixed convention: Branch endpoints DO return specific business-rule 409s
 * (BRANCH_HAS_ACTIVE_TERMINALS/BRANCH_NOT_EMPTY) that must show through untouched - only a real
 * VERSION_CONFLICT errorCode collapses to the generic "updated elsewhere" message. See Round 20's
 * README entry for the exact bug this mirrors avoiding. */
function describeBranchError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.errorCode === 'VERSION_CONFLICT') {
      return 'This branch was changed elsewhere while you were editing. Refresh the page and try again.'
    }
    return err.message || fallback
  }
  return fallback
}

/** Follow-up enhancement ("Local Time Zone During Branch Creation"): same approach as the Admin
 * console's New Branch form - no timezone-data library is bundled in this app, so the full IANA
 * zone list comes from the browser's own Intl.supportedValuesOf (broadly supported in modern
 * browsers). Falls back to a short common-zone list on an older browser, and always includes the
 * branch's current value even if it's somehow outside that list, so the <select> never silently
 * drops the existing selection. */
function getTimezoneOptions(current: string): string[] {
  let zones: string[]
  try {
    zones = Intl.supportedValuesOf('timeZone')
  } catch {
    zones = [
      'Asia/Kolkata', 'Asia/Dubai', 'Asia/Singapore', 'Europe/London', 'Europe/Paris',
      'America/New_York', 'America/Chicago', 'America/Denver', 'America/Los_Angeles', 'UTC',
    ]
  }
  if (current && !zones.includes(current)) {
    zones = [current, ...zones]
  }
  return zones
}

type BranchOption = { id: string; name: string }

/** Phase 2: a uniform row shape for rendering, regardless of whether the caller can reach the
 * real `GET /api/branches` (which requires BRANCH_MANAGE/USER_MANAGE/TERMINAL_MANAGE) - a user
 * without any of those three still sees the same read-only layout this page always showed them,
 * sourced from the restaurant's embedded branch list instead (see BranchesTerminalsPage's own
 * fallback below). branchCode is '' (never shown) in that fallback since only the real endpoint
 * knows it. */
type BranchRow = BranchDto

/** Bistrodesk branch-isolation release (requirement #2, user-confirmed decision): branch
 * *creation* no longer has any POS-reachable path at all - this modal now only ever edits an
 * existing branch (see BranchController's own javadoc for where creation moved to). `branch` is
 * therefore required, not optional, and there is no more create/edit mode switch. */
function BranchFormModal({
  branch,
  onClose,
}: {
  branch: BranchRow
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(branch.name)
  const [address, setAddress] = useState(branch.address ?? '')
  const [phone, setPhone] = useState(branch.phone ?? '')
  // Bistrodesk branch-isolation release (requirement #4, user-confirmed decision: "give each
  // branch its own restaurant profile").
  const [gstin, setGstin] = useState(branch.gstin ?? '')
  const [supportPhone, setSupportPhone] = useState(branch.supportPhone ?? '')
  const [receiptFooterText, setReceiptFooterText] = useState(branch.receiptFooterText ?? '')
  // Follow-up enhancement ("Local Time Zone During Branch Creation"): defaults to the branch's own
  // current zone (always populated once V44/DataSeeder's backfill has run).
  const [timezone, setTimezone] = useState(branch.timezone ?? '')
  // Follow-up requirement #4 (WhatsApp integration): same "give each branch its own config" shape
  // as the restaurant-profile fields above, but for the WhatsApp Business API sender. The raw key
  // is never returned by the server (see BranchDto#whatsappApiKeyConfigured) so whatsappApiKeyNew
  // always starts blank, same convention SettingsPage's aiApiKeyNew/smtpPasswordNew already use.
  const [whatsappProvider, setWhatsappProvider] = useState(branch.whatsappProvider ?? '')
  const [whatsappSenderNumber, setWhatsappSenderNumber] = useState(branch.whatsappSenderNumber ?? '')
  const [whatsappAccountId, setWhatsappAccountId] = useState(branch.whatsappAccountId ?? '')
  const [whatsappApiKeyNew, setWhatsappApiKeyNew] = useState('')
  // POS patch (manual KOT print and order completion): per-branch, off by default - see
  // Branch#manualKotPrintEnabled's javadoc for why this lives here (branch-scoped config) rather
  // than on the install-wide Restaurant Settings screen.
  const [manualKotPrintEnabled, setManualKotPrintEnabled] = useState(branch.manualKotPrintEnabled)
  const [error, setError] = useState<string | null>(null)

  const save = useMutation({
    mutationFn: () =>
      api.patch<BranchDto>(`/branches/${branch.id}`, {
        name: name.trim() || null,
        address: address.trim() || null,
        phone: phone.trim() || null,
        gstin: gstin.trim(),
        supportPhone: supportPhone.trim(),
        receiptFooterText: receiptFooterText.trim(),
        timezone: timezone || null,
        whatsappProvider: whatsappProvider || null,
        whatsappSenderNumber: whatsappSenderNumber.trim(),
        whatsappApiKey: whatsappApiKeyNew.trim() || null,
        whatsappAccountId: whatsappAccountId.trim(),
        manualKotPrintEnabled,
        version: branch.version,
      } satisfies UpdateBranchRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['branches'] })
      queryClient.invalidateQueries({ queryKey: ['restaurant'] })
      onClose()
    },
    onError: (err) => setError(describeBranchError(err, 'Could not update this branch')),
  })

  return (
    <Modal open onClose={onClose} title={`Edit ${branch.name}`} widthClassName="max-w-sm">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          save.mutate()
        }}
      >
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Branch name</label>
          <input
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Downtown"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Address</label>
          <input
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Phone</label>
          <input
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Time zone</label>
          <select
            value={timezone}
            onChange={(e) => setTimezone(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            {!timezone && <option value="">Select a time zone</option>}
            {getTimezoneOptions(timezone).map((tz) => (
              <option key={tz} value={tz}>
                {tz}
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-muted">Used wherever this branch's own date/time (reports, dashboards) is shown.</p>
        </div>
        <div className="border-t border-app pt-4">
          <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-muted">
            Restaurant Profile (this branch)
          </p>
          <p className="mb-3 text-xs text-muted">
            Shown on this branch's own printed receipts. Leave blank to use the Restaurant Settings screen's
            default (used the first time this branch is set up, and never overwritten afterward).
          </p>
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">GSTIN</label>
          <input
            value={gstin}
            onChange={(e) => setGstin(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Support phone (shown on receipts)</label>
          <input
            value={supportPhone}
            onChange={(e) => setSupportPhone(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Receipt footer text</label>
          <textarea
            rows={2}
            value={receiptFooterText}
            onChange={(e) => setReceiptFooterText(e.target.value)}
            placeholder="e.g. Thank you, visit again!"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div className="border-t border-app pt-4">
          <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-muted">
            WhatsApp Business API (this branch)
          </p>
          <p className="mb-3 text-xs text-muted">
            Configure once you've purchased a WhatsApp Business API plan from Meta, Twilio, or
            360dialog, using this branch owner's number as the sender. Until then, sharing a
            purchase order via WhatsApp keeps opening the WhatsApp app instead.
          </p>
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Provider</label>
          <select
            value={whatsappProvider}
            onChange={(e) => setWhatsappProvider(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            <option value="">Not configured</option>
            <option value="META">Meta Cloud API</option>
            <option value="TWILIO">Twilio</option>
            <option value="DIALOG360">360dialog</option>
          </select>
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Sender number (branch owner's WhatsApp Business number)</label>
          <input
            value={whatsappSenderNumber}
            onChange={(e) => setWhatsappSenderNumber(e.target.value)}
            placeholder="e.g. +919876543210"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">
            Account ID (Meta Phone Number ID / Twilio Account SID - not needed for 360dialog)
          </label>
          <input
            value={whatsappAccountId}
            onChange={(e) => setWhatsappAccountId(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <div className="mb-1.5 flex items-center gap-2">
            <label className="block text-xs font-semibold text-muted">API key / auth token</label>
            <Badge tone={branch.whatsappApiKeyConfigured ? 'success' : 'neutral'} className="normal-case">
              {branch.whatsappApiKeyConfigured ? 'Configured' : 'Not configured'}
            </Badge>
          </div>
          <input
            type="password"
            value={whatsappApiKeyNew}
            onChange={(e) => setWhatsappApiKeyNew(e.target.value)}
            placeholder={branch.whatsappApiKeyConfigured ? 'Leave blank to keep the current key' : 'Paste the API key / auth token here'}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            autoComplete="off"
          />
        </div>
        <div className="border-t border-app pt-4">
          <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-muted">
            Billing and Ordering (this branch)
          </p>
          <p className="mb-3 text-xs text-muted">
            Applies only to this branch - turning it on here never affects any other branch's
            ordering workflow.
          </p>
        </div>
        <label className="flex cursor-pointer items-start gap-2.5 rounded-lg border border-app px-3 py-2.5">
          <input
            type="checkbox"
            checked={manualKotPrintEnabled}
            onChange={(e) => setManualKotPrintEnabled(e.target.checked)}
            className="mt-0.5 h-4 w-4 shrink-0 rounded border-app text-brand-500 focus:ring-brand-500"
          />
          <span>
            <span className="block text-sm font-medium text-app">Allow manual KOT print without sending to kitchen</span>
            <span className="mt-0.5 block text-xs text-muted">
              When on, the POS cart offers "Print KOT manually" as an alternative to Send to Kitchen -
              the order never reaches the Kitchen Display, and can be checked out and paid immediately
              with no item status change required. Existing KOT/kitchen behavior is unaffected for any
              branch where this stays off (the default).
            </span>
          </span>
        </label>
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

function DeactivateActivateButton({ branch }: { branch: BranchRow }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const toggle = useMutation({
    mutationFn: () =>
      api.patch<BranchDto>(`/branches/${branch.id}`, {
        active: !branch.active,
        version: branch.version,
      } satisfies UpdateBranchRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['branches'] })
      queryClient.invalidateQueries({ queryKey: ['restaurant'] })
    },
    onError: (err) => setError(describeBranchError(err, 'Could not change this branch\'s status')),
  })

  return (
    <div className="flex flex-col items-end gap-1">
      <Button size="sm" variant="secondary" disabled={toggle.isPending} onClick={() => { setError(null); toggle.mutate() }}>
        {toggle.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : branch.active ? <XCircle className="h-3.5 w-3.5" /> : <CheckCircle2 className="h-3.5 w-3.5" />}
        {branch.active ? 'Deactivate' : 'Activate'}
      </Button>
      {error && <span className="max-w-[16rem] text-right text-[11px] font-medium text-danger">{error}</span>}
    </div>
  )
}

function DeleteBranchModal({ branch, onClose }: { branch: BranchRow; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const del = useMutation({
    mutationFn: () => api.delete<void>(`/branches/${branch.id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['branches'] })
      queryClient.invalidateQueries({ queryKey: ['restaurant'] })
      onClose()
    },
    onError: (err) => setError(describeBranchError(err, 'Could not delete this branch')),
  })

  return (
    <Modal open onClose={onClose} title={`Delete "${branch.name}"?`} widthClassName="max-w-sm">
      <div className="space-y-4">
        <p className="text-sm text-muted">
          This permanently removes the branch. It only succeeds if it has no terminals registered (active or
          inactive) and no floors/tables configured.
        </p>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" className="flex-1" disabled={del.isPending} onClick={() => del.mutate()}>
            {del.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
            {del.isPending ? 'Deleting…' : 'Delete branch'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

function AddTerminalModal({ branchId, onClose }: { branchId: string; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () =>
      api.post<TerminalDto>(`/branches/${branchId}/terminals`, {
        name: name.trim() || null,
      } satisfies CreateTerminalRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['terminals'] })
      queryClient.invalidateQueries({ queryKey: ['branches'] })
      onClose()
    },
    onError: (err) => setError(describeBranchError(err, 'Could not add a terminal to this branch')),
  })

  return (
    <Modal open onClose={onClose} title="Add Terminal" widthClassName="max-w-sm">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          create.mutate()
        }}
      >
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Name (optional)</label>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Leave blank for an auto-numbered name"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button type="button" variant="secondary" className="flex-1" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" className="flex-1" disabled={create.isPending}>
            {create.isPending ? 'Adding…' : 'Add terminal'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

/** Item 7's "how many terminals do you want?" prompt. */
function BulkCreateTerminalsModal({ branchId, onClose }: { branchId: string; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [count, setCount] = useState(1)
  const [namePrefix, setNamePrefix] = useState('')
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () =>
      api.post<TerminalDto[]>(`/branches/${branchId}/terminals:bulk`, {
        count,
        namePrefix: namePrefix.trim() || null,
      } satisfies BulkCreateTerminalsRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['terminals'] })
      queryClient.invalidateQueries({ queryKey: ['branches'] })
      onClose()
    },
    onError: (err) => setError(describeBranchError(err, 'Could not set up terminals for this branch')),
  })

  return (
    <Modal open onClose={onClose} title="Set Up Terminals" widthClassName="max-w-sm">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          create.mutate()
        }}
      >
        <p className="text-sm text-muted">How many terminals (tills) do you want to set up at this branch?</p>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Number of terminals (1-50)</label>
          <input
            type="number"
            min={1}
            max={50}
            required
            value={count}
            onChange={(e) => setCount(Math.max(1, Math.min(50, Number(e.target.value) || 1)))}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Name prefix (optional)</label>
          <input
            value={namePrefix}
            onChange={(e) => setNamePrefix(e.target.value)}
            placeholder='e.g. "Counter" -> Counter 001, Counter 002…'
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button type="button" variant="secondary" className="flex-1" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" className="flex-1" disabled={create.isPending}>
            {create.isPending ? 'Setting up…' : `Create ${count} terminal${count === 1 ? '' : 's'}`}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function TerminalEditForm({
  terminal,
  branches,
  onCancel,
}: {
  terminal: TerminalDto
  branches: BranchOption[]
  onCancel: () => void
}) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(terminal.name)
  const [branchId, setBranchId] = useState(terminal.branchId ?? '')
  const [active, setActive] = useState(terminal.active)
  const [error, setError] = useState<string | null>(null)

  const update = useMutation({
    mutationFn: () =>
      api.put<TerminalDto>(`/terminals/${terminal.id}`, {
        name: name.trim() || null,
        branchId: branchId || null,
        active,
        version: terminal.version,
      } satisfies UpdateTerminalRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['terminals'] })
      queryClient.invalidateQueries({ queryKey: ['branches'] })
      onCancel()
    },
    onError: (err) => setError(describeTerminalError(err, 'Could not save this terminal')),
  })

  return (
    <form
      className="space-y-3 rounded-xl border border-app bg-app/40 p-3"
      onSubmit={(e) => {
        e.preventDefault()
        setError(null)
        update.mutate()
      }}
    >
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Name</label>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Terminal name"
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Branch</label>
          <select
            value={branchId}
            onChange={(e) => setBranchId(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            <option value="">Unassigned</option>
            {branches.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Status</label>
          <select
            value={active ? 'active' : 'inactive'}
            onChange={(e) => setActive(e.target.value === 'active')}
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            <option value="active">Active</option>
            <option value="inactive">Inactive (retired)</option>
          </select>
        </div>
      </div>

      {error && <div className="rounded-lg bg-danger-soft px-3 py-1.5 text-xs font-medium text-danger">{error}</div>}

      <div className="flex justify-end gap-2">
        <Button type="button" size="sm" variant="secondary" onClick={onCancel} disabled={update.isPending}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={update.isPending}>
          {update.isPending ? 'Saving…' : 'Save'}
        </Button>
      </div>
    </form>
  )
}

function TerminalRow({
  terminal,
  branches,
  canManage,
}: {
  terminal: TerminalDto
  branches: BranchOption[]
  canManage: boolean
}) {
  const [editing, setEditing] = useState(false)

  if (editing) {
    return <TerminalEditForm terminal={terminal} branches={branches} onCancel={() => setEditing(false)} />
  }

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-app bg-app/40 px-3 py-2.5">
      <div className="flex min-w-0 items-center gap-2.5">
        <MonitorSmartphone className="h-4 w-4 shrink-0 text-muted" />
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-1.5 text-sm font-semibold text-app">
            <span className="truncate">{terminal.name}</span>
            {terminal.terminalCode && (
              <span className="shrink-0 rounded bg-app px-1.5 py-0.5 font-mono text-[10px] font-bold text-muted">
                {terminal.terminalCode}
              </span>
            )}
          </div>
          <div className="truncate text-xs text-muted">
            {terminal.type ?? 'Unknown type'}
            {' · '}
            {terminal.lastUserDisplayName ? `Last used by ${terminal.lastUserDisplayName}` : 'Never used'}
            {terminal.lastSeenAt && ` · ${formatDateTime(terminal.lastSeenAt)}`}
          </div>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <Badge tone={terminal.active ? 'success' : 'neutral'}>
          {terminal.active ? <CheckCircle2 className="mr-1 h-3 w-3" /> : <XCircle className="mr-1 h-3 w-3" />}
          {terminal.active ? 'Active' : 'Inactive'}
        </Badge>
        {canManage && (
          <Button type="button" size="sm" variant="secondary" onClick={() => setEditing(true)}>
            <Pencil className="h-3.5 w-3.5" /> Edit
          </Button>
        )}
      </div>
    </div>
  )
}

/** Round 17: read-only terminal listing used by the 0-1-branch (static) layout - "if no multiple
 * branches it will be static" per the user's own request, so no rename/reassign/retire affordance
 * is offered here at all regardless of permission, even though the endpoint would allow it. Phase
 * 2 note: this restriction is specifically about editing an EXISTING terminal - it does not apply
 * to the new create/bulk-create actions above, which a single-branch restaurant needs exactly as
 * much as a multi-branch one and never existed before this round either way. */
function ReadOnlyTerminalRow({ terminal }: { terminal: TerminalDto }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-app bg-app/40 px-3 py-2.5">
      <div className="flex min-w-0 items-center gap-2.5">
        <MonitorSmartphone className="h-4 w-4 shrink-0 text-muted" />
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-1.5 text-sm font-semibold text-app">
            <span className="truncate">{terminal.name}</span>
            {terminal.terminalCode && (
              <span className="shrink-0 rounded bg-app px-1.5 py-0.5 font-mono text-[10px] font-bold text-muted">
                {terminal.terminalCode}
              </span>
            )}
          </div>
          <div className="truncate text-xs text-muted">
            {terminal.type ?? 'Unknown type'}
            {' · '}
            {terminal.lastUserDisplayName ? `Last used by ${terminal.lastUserDisplayName}` : 'Never used'}
            {terminal.lastSeenAt && ` · ${formatDateTime(terminal.lastSeenAt)}`}
          </div>
        </div>
      </div>
      <Badge tone={terminal.active ? 'success' : 'neutral'} className="shrink-0">
        {terminal.active ? <CheckCircle2 className="mr-1 h-3 w-3" /> : <XCircle className="mr-1 h-3 w-3" />}
        {terminal.active ? 'Active' : 'Inactive'}
      </Badge>
    </div>
  )
}

function BranchCard({
  branch,
  terminals,
  branchOptions,
  canManageBranches,
  canManageTerminals,
  allowTerminalEdit,
  onEdit,
  onDelete,
}: {
  branch: BranchRow
  terminals: TerminalDto[]
  branchOptions: BranchOption[]
  canManageBranches: boolean
  canManageTerminals: boolean
  allowTerminalEdit: boolean
  onEdit: () => void
  onDelete: () => void
}) {
  const [addOpen, setAddOpen] = useState(false)
  const [bulkOpen, setBulkOpen] = useState(false)

  return (
    <Card>
      <CardHeader className="flex-wrap gap-2">
        <div>
          <CardTitle className="flex flex-wrap items-center gap-2">
            <Building2 className="h-4 w-4 text-brand-600" /> {branch.name}
            {branch.branchCode && (
              <span className="rounded bg-app px-1.5 py-0.5 font-mono text-[10px] font-bold text-muted">
                {branch.branchCode}
              </span>
            )}
            {!branch.active && <Badge tone="neutral">Inactive</Badge>}
          </CardTitle>
          {(branch.address || branch.phone) && (
            <p className="mt-0.5 text-xs text-muted">{[branch.address, branch.phone].filter(Boolean).join(' · ')}</p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="neutral">
            {terminals.length} terminal{terminals.length === 1 ? '' : 's'}
          </Badge>
          {canManageBranches && (
            <>
              <Button size="sm" variant="secondary" onClick={onEdit}>
                <Pencil className="h-3.5 w-3.5" /> Edit
              </Button>
              <DeactivateActivateButton branch={branch} />
              <Button size="sm" variant="secondary" onClick={onDelete} title="Delete branch">
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </>
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-2">
        {canManageTerminals && (
          <div className="flex flex-wrap gap-2 pb-1">
            <Button size="sm" variant="secondary" onClick={() => setAddOpen(true)}>
              <Plus className="h-3.5 w-3.5" /> Add Terminal
            </Button>
            <Button size="sm" variant="secondary" onClick={() => setBulkOpen(true)}>
              <Users className="h-3.5 w-3.5" /> Set up terminals
            </Button>
          </div>
        )}
        {terminals.length === 0 ? (
          <p className="py-3 text-center text-sm text-muted">No terminals assigned to this branch yet.</p>
        ) : (
          terminals.map((t) =>
            allowTerminalEdit ? (
              <TerminalRow key={t.id} terminal={t} branches={branchOptions} canManage />
            ) : (
              <ReadOnlyTerminalRow key={t.id} terminal={t} />
            ),
          )
        )}
      </CardContent>
      {addOpen && <AddTerminalModal branchId={branch.id} onClose={() => setAddOpen(false)} />}
      {bulkOpen && <BulkCreateTerminalsModal branchId={branch.id} onClose={() => setBulkOpen(false)} />}
    </Card>
  )
}

export function BranchesTerminalsPage() {
  const { hasPermission, hasPasswordLogin } = useAuthStore()
  const canViewBranchMgmt = hasPermission('BRANCH_MANAGE') || hasPermission('USER_MANAGE') || hasPermission('TERMINAL_MANAGE')
  const canManageBranches = hasPermission('BRANCH_MANAGE') && hasPasswordLogin()
  const branchPwGateBlocked = hasPermission('BRANCH_MANAGE') && !hasPasswordLogin()
  const canManageTerminals = (hasPermission('TERMINAL_MANAGE') || hasPermission('RESTAURANT_MANAGE')) && hasPasswordLogin()
  const terminalPwGateBlocked = (hasPermission('TERMINAL_MANAGE') || hasPermission('RESTAURANT_MANAGE')) && !hasPasswordLogin()

  const [branchModal, setBranchModal] = useState<BranchRow | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<BranchRow | null>(null)

  const restaurantQuery = useQuery({
    queryKey: ['restaurant'],
    queryFn: () => api.get<RestaurantDto>('/restaurant'),
    staleTime: 60_000,
  })
  const branchesQuery = useQuery({
    queryKey: ['branches'],
    queryFn: () => api.get<BranchDto[]>('/branches'),
    enabled: canViewBranchMgmt,
  })
  const terminalsQuery = useQuery({
    queryKey: ['terminals'],
    queryFn: () => api.get<TerminalDto[]>('/terminals'),
  })

  if (restaurantQuery.isLoading || terminalsQuery.isLoading || (canViewBranchMgmt && branchesQuery.isLoading)) {
    return <FullPageSpinner label="Loading branches & terminals…" />
  }

  if (restaurantQuery.isError || terminalsQuery.isError || (canViewBranchMgmt && branchesQuery.isError)) {
    const err = restaurantQuery.error ?? terminalsQuery.error ?? branchesQuery.error
    return (
      <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
        {err instanceof ApiError ? err.message : 'Could not load branches and terminals'}
      </div>
    )
  }

  const restaurant = restaurantQuery.data!
  const terminals = terminalsQuery.data ?? []
  // Phase 2: prefer the real GET /api/branches (branch_code, active, terminalCount) for anyone
  // holding one of the three permissions it requires; everyone else keeps seeing exactly what
  // this page always showed them - the restaurant's embedded branch list - so a Waiter/Cashier
  // opening this screen never regresses to a 403.
  const branches: BranchRow[] =
    canViewBranchMgmt && branchesQuery.data
      ? branchesQuery.data
      : restaurant.branches.map((b) => ({
          id: b.id,
          name: b.name,
          branchCode: '',
          address: b.address,
          phone: b.phone,
          active: true,
          terminalCount: terminals.filter((t) => t.branchId === b.id).length,
          version: b.version,
          // Bistrodesk branch-isolation release (requirement #4): this restaurant-embedded summary
          // never carried these - not shown in this read-only fallback view anyway (a Waiter/
          // Cashier without branch-management permission never opens the edit form that reads them).
          gstin: null,
          supportPhone: null,
          receiptFooterText: null,
          logoImageBase64: null,
          whatsappProvider: null,
          whatsappSenderNumber: null,
          whatsappAccountId: null,
          whatsappApiKeyConfigured: false,
          timezone: null,
          // POS patch (manual KOT print and order completion): unlike the other branch-profile
          // fields above, this restaurant-embedded summary DOES carry it (see RestaurantDto's
          // nested BranchDto) - it's the one field this fallback view's own toggle actually reads.
          manualKotPrintEnabled: b.manualKotPrintEnabled,
        }))
  const multiBranch = branches.length >= 2
  const refreshing = restaurantQuery.isFetching || terminalsQuery.isFetching || branchesQuery.isFetching

  const refresh = () => {
    restaurantQuery.refetch()
    terminalsQuery.refetch()
    if (canViewBranchMgmt) branchesQuery.refetch()
  }

  const branchOptions: BranchOption[] = branches.map((b) => ({ id: b.id, name: b.name }))

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold text-app">
            <Building2 className="h-5 w-5 text-brand-600" /> Branches & Terminals
          </h2>
          <p className="mt-0.5 text-sm text-muted">
            {multiBranch
              ? 'Manage each branch and see which terminal is registered where.'
              : 'A single-location setup - one branch, with every registered terminal listed below.'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={refresh}>
            <RefreshCw className={cn('h-3.5 w-3.5', refreshing && 'animate-spin')} /> Refresh
          </Button>
        </div>
      </div>

      {(branchPwGateBlocked || terminalPwGateBlocked) && (
        <div className="flex items-start gap-2 rounded-lg border border-info/30 bg-info-soft px-3 py-2.5 text-xs text-info">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            You have permission to manage branches/terminals, but this requires signing in with a username and
            password, not a PIN. Sign in again from the Manager/Admin login screen.
          </span>
        </div>
      )}

      {branches.length === 0 ? (
        <Card className="flex flex-col items-center gap-2 px-6 py-16 text-center">
          <Building2 className="h-8 w-8 text-muted" />
          <p className="text-sm text-muted">No branches yet.</p>
          <p className="max-w-xs text-xs text-muted">
            Branches are created from the Bistrodesk Admin console, not from this app. Contact Bistrodesk support to
            add your first branch.
          </p>
        </Card>
      ) : (
        <div className="space-y-4">
          {branches.map((branch) => (
            <BranchCard
              key={branch.id}
              branch={branch}
              terminals={terminals.filter((t) => t.branchId === branch.id)}
              branchOptions={branchOptions}
              canManageBranches={canManageBranches}
              canManageTerminals={canManageTerminals}
              allowTerminalEdit={canManageTerminals && multiBranch}
              onEdit={() => setBranchModal(branch)}
              onDelete={() => setDeleteTarget(branch)}
            />
          ))}

          {(() => {
            const unassigned = terminals.filter((t) => !t.branchId)
            if (unassigned.length === 0) return null
            return (
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-muted">
                    <MonitorSmartphone className="h-4 w-4" /> Unassigned Terminals
                  </CardTitle>
                  <Badge tone="warning">{unassigned.length}</Badge>
                </CardHeader>
                <CardContent className="space-y-2">
                  {unassigned.map((t) =>
                    canManageTerminals && multiBranch ? (
                      <TerminalRow key={t.id} terminal={t} branches={branchOptions} canManage />
                    ) : (
                      <ReadOnlyTerminalRow key={t.id} terminal={t} />
                    ),
                  )}
                </CardContent>
              </Card>
            )
          })()}
        </div>
      )}

      {!canManageTerminals && multiBranch && (
        <p className="text-xs text-muted">Ask a manager for TERMINAL_MANAGE permission to rename, reassign, or retire a terminal.</p>
      )}

      {branchModal && <BranchFormModal branch={branchModal} onClose={() => setBranchModal(null)} />}
      {deleteTarget && <DeleteBranchModal branch={deleteTarget} onClose={() => setDeleteTarget(null)} />}
    </div>
  )
}
