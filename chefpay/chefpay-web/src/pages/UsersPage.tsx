import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertTriangle,
  Building2,
  Hash,
  KeyRound,
  Lock,
  MonitorSmartphone,
  Plus,
  RefreshCw,
  Search,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  UserCog,
  Users2,
} from 'lucide-react'
import { useMemo, useState } from 'react'

import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Modal } from '@/components/ui/Modal'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { api } from '@/lib/api'
import { cn, formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import { ApiError } from '@/types/api'
import type {
  ChangePinRequest,
  ChangeUserCodeRequest,
  CreateUserRequest,
  RestaurantDto,
  SuggestedCredentialsResponse,
  TerminalDto,
  UpdateUserBranchesRequest,
  UpdateUserRequest,
  UpdateUserTerminalsRequest,
  UserDto,
} from '@/types/api'

// The backend seeds these seven roles (see com.chefpay.server.config.DataSeeder) and there is no
// POST /roles endpoint - roles can't be created or renamed from this screen, only the permission
// set of each already-existing role can be edited. Phase 2 adds OWNER: full permissions like
// ADMIN, but the one role the server refuses to ever let anyone deactivate (see
// UserAccountService#updateUser) - EditStaffModal below hides the "Account active" toggle for it
// rather than let a manager hit that 400 blind.
const ROLE_NAMES = ['OWNER', 'ADMIN', 'MANAGER', 'CASHIER', 'WAITER', 'KITCHEN', 'VIEW_ONLY'] as const

const ROLE_TONE: Record<string, 'brand' | 'info' | 'success' | 'warning' | 'neutral'> = {
  OWNER: 'brand',
  ADMIN: 'brand',
  MANAGER: 'info',
  CASHIER: 'success',
  WAITER: 'warning',
  KITCHEN: 'neutral',
  VIEW_ONLY: 'neutral',
}

/** Renders a real backend role/permission code as readable Title Case, e.g. 'VIEW_ONLY' -> 'View
 * Only', 'PURCHASE_ORDER_APPROVE' -> 'Purchase Order Approve'. Purely cosmetic - the raw code is
 * always what's sent back to the API. */
function titleCase(code: string): string {
  return code
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

/** Round 20's fixed convention (see that round's README entry): only collapse a genuine
 * optimistic-lock conflict, never a blanket 403/409 - a PW-required 403's specific message, or
 * the OWNER-can't-be-deactivated 400, must show through untouched. */
function friendlyErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.errorCode === 'VERSION_CONFLICT') {
      return 'This record changed elsewhere while you were editing it. Refresh and try again.'
    }
    return err.message
  }
  return fallback
}

function AddStaffModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [userCode, setUserCode] = useState('')
  const [pin, setPin] = useState('')
  const [role, setRole] = useState<string>('WAITER')
  const [error, setError] = useState<string | null>(null)

  function reset() {
    setUsername('')
    setDisplayName('')
    setPassword('')
    setUserCode('')
    setPin('')
    setRole('WAITER')
    setError(null)
  }

  const suggest = useMutation({
    mutationFn: () => api.get<SuggestedCredentialsResponse>('/users/suggested-credentials', { role }),
    onSuccess: (s) => {
      setUserCode(s.userCode)
      setPin(s.pin)
    },
    onError: (err) => setError(friendlyErrorMessage(err, 'Could not generate suggested credentials')),
  })

  const createUser = useMutation({
    mutationFn: () =>
      api.post<UserDto>('/users', {
        username: username.trim(),
        displayName: displayName.trim(),
        password,
        pin: pin.trim() ? pin.trim() : undefined,
        role,
        userCode: userCode.trim() ? userCode.trim() : undefined,
      } satisfies CreateUserRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      reset()
      onClose()
    },
    onError: (err) => setError(friendlyErrorMessage(err, 'Could not create this staff account')),
  })

  return (
    <Modal
      open={open}
      onClose={() => {
        reset()
        onClose()
      }}
      title="Add Staff Member"
      widthClassName="max-w-md"
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          createUser.mutate()
        }}
      >
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Display name</label>
          <input
            required
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="e.g. Priya Sharma"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Username</label>
          <input
            required
            autoComplete="off"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="e.g. priya"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Password</label>
          <div className="relative">
            <KeyRound className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <input
              required
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-lg border border-app bg-app py-2 pl-9 pr-3 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Role</label>
          <select
            value={role}
            onChange={(e) => setRole(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            {ROLE_NAMES.map((r) => (
              <option key={r} value={r}>
                {titleCase(r)}
              </option>
            ))}
          </select>
        </div>

        <div className="rounded-xl border border-app p-3">
          <div className="mb-2 flex items-center justify-between">
            <p className="text-xs font-semibold text-muted">POS sign-in (User Code + PIN)</p>
            <button
              type="button"
              onClick={() => suggest.mutate()}
              disabled={suggest.isPending}
              className="flex items-center gap-1 text-[11px] font-semibold text-brand-600 hover:underline disabled:opacity-50 dark:text-brand-400"
            >
              <Sparkles className="h-3 w-3" /> {suggest.isPending ? 'Generating…' : 'Auto-generate'}
            </button>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="mb-1 block text-[11px] font-semibold text-muted">User code</label>
              <input
                autoComplete="off"
                value={userCode}
                onChange={(e) => setUserCode(e.target.value.toUpperCase())}
                placeholder="Auto"
                className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm uppercase tracking-wide text-app outline-none focus:ring-2 focus:ring-brand-500"
              />
            </div>
            <div>
              <label className="mb-1 block text-[11px] font-semibold text-muted">Quick PIN</label>
              <input
                type="password"
                inputMode="numeric"
                autoComplete="off"
                value={pin}
                onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
                placeholder="Optional"
                className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm tracking-widest text-app outline-none focus:ring-2 focus:ring-brand-500"
              />
            </div>
          </div>
          <p className="mt-1.5 text-[11px] text-muted">Leave blank to auto-generate a code when saved; a PIN is optional.</p>
        </div>

        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <Button type="submit" className="w-full" disabled={createUser.isPending}>
          <Plus className="h-4 w-4" /> {createUser.isPending ? 'Adding…' : 'Add staff member'}
        </Button>
      </form>
    </Modal>
  )
}

function EditStaffModal({ user, onClose }: { user: UserDto; onClose: () => void }) {
  const queryClient = useQueryClient()
  const { userId: currentUserId } = useAuthStore()
  const [displayName, setDisplayName] = useState(user.displayName)
  const [role, setRole] = useState(user.role)
  const [active, setActive] = useState(user.active)
  const [newPassword, setNewPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  const isSelf = currentUserId != null && currentUserId === user.id
  const isDeactivatingSelf = isSelf && user.active && !active
  const isOwner = user.role === 'OWNER'

  const updateUser = useMutation({
    mutationFn: () => {
      const body: UpdateUserRequest = {
        displayName: displayName.trim(),
        role,
        active: isOwner ? true : active,
        version: user.version,
      }
      if (newPassword.trim()) body.newPassword = newPassword.trim()
      return api.patch<UserDto>(`/users/${user.id}`, body)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      onClose()
    },
    onError: (err) => setError(friendlyErrorMessage(err, 'Could not update this staff account')),
  })

  return (
    <Modal open onClose={onClose} title={`Edit ${user.displayName}`} widthClassName="max-w-md">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          updateUser.mutate()
        }}
      >
        <div className="flex flex-wrap items-center gap-2 rounded-lg bg-app/50 px-3 py-2 text-xs text-muted">
          <span className="font-semibold text-app">@{user.username}</span>
          {user.userCode && (
            <span className="rounded bg-app px-1.5 py-0.5 font-mono text-[10px] font-bold text-muted">
              {user.userCode}
            </span>
          )}
          {user.createdAt && <span>· Joined {formatDateTime(user.createdAt)}</span>}
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Display name</label>
          <input
            required
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Role</label>
          <select
            value={role}
            onChange={(e) => setRole(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            {ROLE_NAMES.map((r) => (
              <option key={r} value={r}>
                {titleCase(r)}
              </option>
            ))}
          </select>
        </div>

        {isOwner ? (
          <div className="flex items-start gap-2 rounded-lg border border-info/30 bg-info-soft px-3 py-2 text-xs text-info">
            <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
            <span>Owner accounts can't be deactivated by anyone - this is enforced by the server.</span>
          </div>
        ) : (
          <label className="flex items-center justify-between rounded-lg border border-app px-3 py-2.5">
            <span className="text-sm font-medium text-app">Account active</span>
            <input
              type="checkbox"
              checked={active}
              onChange={(e) => setActive(e.target.checked)}
              className="h-4 w-4 accent-brand-600"
            />
          </label>
        )}

        {isDeactivatingSelf && (
          <div className="flex items-start gap-2 rounded-lg border border-warning/30 bg-warning-soft px-3 py-2 text-xs text-warning">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>You're about to deactivate your own account. You'll be signed out and may lose access immediately.</span>
          </div>
        )}

        <div className="border-t border-app pt-4">
          <label className="mb-1.5 block text-xs font-semibold text-muted">
            New password <span className="font-normal normal-case text-muted">(leave blank to keep unchanged)</span>
          </label>
          <input
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            placeholder="•••••••• (unchanged)"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
          <p className="mt-2 text-[11px] text-muted">
            To reset this person's PIN or login code instead, use the PIN/Code actions from the staff list - they're
            kept separate so resetting one never touches anything else about this account.
          </p>
        </div>

        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

        <div className="flex gap-2">
          <Button type="button" variant="secondary" className="flex-1" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" className="flex-1" disabled={updateUser.isPending}>
            {updateUser.isPending ? 'Saving…' : 'Save changes'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

/** Item 9's "Change PIN" as its own action, distinct from EditStaffModal above - a manager
 * resetting a forgotten PIN never has to touch (or accidentally overwrite) anything else. */
function ChangePinModal({ user, onClose }: { user: UserDto; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [pin, setPin] = useState('')
  const [error, setError] = useState<string | null>(null)

  const suggest = useMutation({
    mutationFn: () => api.get<SuggestedCredentialsResponse>('/users/suggested-credentials', { role: user.role }),
    onSuccess: (s) => setPin(s.pin),
    onError: (err) => setError(friendlyErrorMessage(err, 'Could not generate a suggested PIN')),
  })

  const changePin = useMutation({
    mutationFn: () => api.patch<UserDto>(`/users/${user.id}/pin`, { newPin: pin.trim() } satisfies ChangePinRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      onClose()
    },
    onError: (err) => setError(friendlyErrorMessage(err, "Could not change this user's PIN")),
  })

  return (
    <Modal open onClose={onClose} title={`Change PIN - ${user.displayName}`} widthClassName="max-w-sm">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          changePin.mutate()
        }}
      >
        <div className="flex items-center justify-between">
          <label className="text-xs font-semibold text-muted">New PIN</label>
          <button
            type="button"
            onClick={() => suggest.mutate()}
            disabled={suggest.isPending}
            className="flex items-center gap-1 text-[11px] font-semibold text-brand-600 hover:underline disabled:opacity-50 dark:text-brand-400"
          >
            <Sparkles className="h-3 w-3" /> {suggest.isPending ? 'Generating…' : 'Auto-generate'}
          </button>
        </div>
        <input
          required
          type="password"
          inputMode="numeric"
          autoComplete="off"
          autoFocus
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
          placeholder="New PIN"
          className="w-full rounded-lg border border-app bg-app px-3 py-2 text-center text-lg tracking-widest text-app outline-none focus:ring-2 focus:ring-brand-500"
        />
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button type="button" variant="secondary" className="flex-1" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" className="flex-1" disabled={changePin.isPending || !pin.trim()}>
            {changePin.isPending ? 'Saving…' : 'Change PIN'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

/** Item 9/14's "view/edit a user's userCode" + branch assignment + the new terminal-assignment
 * control, each independently saved (three separate endpoints/audit actions) - kept in one modal
 * only because they're all "who can this account reach" questions, not because they're one action.
 * `liveUser` tracks the most recent server response so a second save in the same modal session
 * always sends the current `version`, rather than the one this modal was opened with. */
function AccessModal({
  user,
  branchOptions,
  terminalOptions,
  canEditUserCode,
  canEditBranches,
  canEditTerminals,
  onClose,
}: {
  user: UserDto
  branchOptions: { id: string; name: string }[]
  terminalOptions: TerminalDto[]
  canEditUserCode: boolean
  canEditBranches: boolean
  canEditTerminals: boolean
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [liveUser, setLiveUser] = useState(user)
  const [userCodeDraft, setUserCodeDraft] = useState(user.userCode ?? '')
  const [userCodeError, setUserCodeError] = useState<string | null>(null)
  const [branchIds, setBranchIds] = useState<Set<string>>(new Set(user.branchIds))
  const [branchError, setBranchError] = useState<string | null>(null)
  const [terminalIds, setTerminalIds] = useState<Set<string>>(new Set(user.terminalIds))
  const [terminalError, setTerminalError] = useState<string | null>(null)

  function closeAndRefresh() {
    queryClient.invalidateQueries({ queryKey: ['users'] })
    onClose()
  }

  const saveUserCode = useMutation({
    mutationFn: () =>
      api.patch<UserDto>(`/users/${user.id}/user-code`, {
        newUserCode: userCodeDraft.trim(),
        version: liveUser.version,
      } satisfies ChangeUserCodeRequest),
    onSuccess: (dto) => {
      setLiveUser(dto)
      setUserCodeError(null)
    },
    onError: (err) => setUserCodeError(friendlyErrorMessage(err, 'Could not change the login code')),
  })

  const saveBranches = useMutation({
    mutationFn: () =>
      api.patch<UserDto>(`/users/${user.id}/branches`, {
        branchIds: [...branchIds],
        defaultBranchId: liveUser.defaultBranchId,
        version: liveUser.version,
      } satisfies UpdateUserBranchesRequest),
    onSuccess: (dto) => {
      setLiveUser(dto)
      setBranchError(null)
    },
    onError: (err) => setBranchError(friendlyErrorMessage(err, 'Could not update branch access')),
  })

  const saveTerminals = useMutation({
    mutationFn: () =>
      api.patch<UserDto>(`/users/${user.id}/terminals`, {
        terminalIds: [...terminalIds],
        version: liveUser.version,
      } satisfies UpdateUserTerminalsRequest),
    onSuccess: (dto) => {
      setLiveUser(dto)
      setTerminalError(null)
    },
    onError: (err) => setTerminalError(friendlyErrorMessage(err, 'Could not update terminal access')),
  })

  function toggle(set: Set<string>, setSet: (s: Set<string>) => void, id: string) {
    const next = new Set(set)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSet(next)
  }

  return (
    <Modal open onClose={closeAndRefresh} title={`Access - ${user.displayName}`} widthClassName="max-w-lg">
      <div className="space-y-6">
        <div>
          <div className="mb-1.5 flex items-center justify-between">
            <label className="flex items-center gap-1.5 text-xs font-semibold text-muted">
              <Hash className="h-3.5 w-3.5" /> User Code (POS login identifier)
            </label>
          </div>
          <div className="flex gap-2">
            <input
              value={userCodeDraft}
              disabled={!canEditUserCode}
              onChange={(e) => setUserCodeDraft(e.target.value.toUpperCase())}
              className="flex-1 rounded-lg border border-app bg-app px-3 py-2 text-sm uppercase tracking-wide text-app outline-none focus:ring-2 focus:ring-brand-500 disabled:opacity-60"
            />
            {canEditUserCode && (
              <Button
                type="button"
                size="sm"
                disabled={saveUserCode.isPending || !userCodeDraft.trim() || userCodeDraft.trim() === (liveUser.userCode ?? '')}
                onClick={() => saveUserCode.mutate()}
              >
                {saveUserCode.isPending ? 'Saving…' : 'Save'}
              </Button>
            )}
          </div>
          {userCodeError && <p className="mt-1 text-xs font-medium text-danger">{userCodeError}</p>}
        </div>

        {canEditBranches && (
          <div>
            <div className="mb-1.5 flex items-center justify-between">
              <label className="flex items-center gap-1.5 text-xs font-semibold text-muted">
                <Building2 className="h-3.5 w-3.5" /> Branch access
              </label>
              <Button type="button" size="sm" disabled={saveBranches.isPending} onClick={() => saveBranches.mutate()}>
                {saveBranches.isPending ? 'Saving…' : 'Save'}
              </Button>
            </div>
            <p className="mb-2 text-[11px] text-muted">Leave everything unchecked for unrestricted access to every branch.</p>
            <div className="max-h-40 space-y-0.5 overflow-y-auto rounded-lg border border-app p-2">
              {branchOptions.length === 0 && <p className="px-1.5 py-1 text-xs text-muted">No branches configured.</p>}
              {branchOptions.map((b) => (
                <label key={b.id} className="flex cursor-pointer items-center gap-2 rounded px-1.5 py-1 text-sm hover:bg-app/50">
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-brand-600"
                    checked={branchIds.has(b.id)}
                    onChange={() => toggle(branchIds, setBranchIds, b.id)}
                  />
                  <span className="text-app">{b.name}</span>
                </label>
              ))}
            </div>
            {branchError && <p className="mt-1 text-xs font-medium text-danger">{branchError}</p>}
          </div>
        )}

        {canEditTerminals && (
          <div>
            <div className="mb-1.5 flex items-center justify-between">
              <label className="flex items-center gap-1.5 text-xs font-semibold text-muted">
                <MonitorSmartphone className="h-3.5 w-3.5" /> Terminal access
              </label>
              <Button type="button" size="sm" disabled={saveTerminals.isPending} onClick={() => saveTerminals.mutate()}>
                {saveTerminals.isPending ? 'Saving…' : 'Save'}
              </Button>
            </div>
            <p className="mb-2 text-[11px] text-muted">Leave everything unchecked for unrestricted access to every terminal.</p>
            <div className="max-h-40 space-y-0.5 overflow-y-auto rounded-lg border border-app p-2">
              {terminalOptions.length === 0 && <p className="px-1.5 py-1 text-xs text-muted">No terminals registered yet.</p>}
              {terminalOptions.map((t) => (
                <label key={t.id} className="flex cursor-pointer items-center gap-2 rounded px-1.5 py-1 text-sm hover:bg-app/50">
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-brand-600"
                    checked={terminalIds.has(t.id)}
                    onChange={() => toggle(terminalIds, setTerminalIds, t.id)}
                  />
                  <span className="text-app">{t.name}</span>
                  {t.branchName && <span className="text-xs text-muted">· {t.branchName}</span>}
                </label>
              ))}
            </div>
            {terminalError && <p className="mt-1 text-xs font-medium text-danger">{terminalError}</p>}
          </div>
        )}

        <div className="flex justify-end">
          <Button type="button" variant="secondary" onClick={closeAndRefresh}>
            Done
          </Button>
        </div>
      </div>
    </Modal>
  )
}

function StaffDirectory({
  canManageUsers,
  canEditBranches,
  canEditTerminals,
  hasPasswordLogin,
}: {
  canManageUsers: boolean
  canEditBranches: boolean
  canEditTerminals: boolean
  hasPasswordLogin: boolean
}) {
  const [search, setSearch] = useState('')
  const [roleFilter, setRoleFilter] = useState<string>('ALL')
  const [addOpen, setAddOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<UserDto | null>(null)
  const [pinTarget, setPinTarget] = useState<UserDto | null>(null)
  const [accessTarget, setAccessTarget] = useState<UserDto | null>(null)
  const { userId: currentUserId } = useAuthStore()

  const usersQuery = useQuery({
    queryKey: ['users'],
    queryFn: () => api.get<UserDto[]>('/users'),
  })
  // Reused across BranchesTerminalsPage/AccessModal via the same query keys - react-query shares
  // one cached fetch instead of every screen re-requesting the same list.
  const restaurantQuery = useQuery({
    queryKey: ['restaurant'],
    queryFn: () => api.get<RestaurantDto>('/restaurant'),
    staleTime: 60_000,
    enabled: canEditBranches || canEditTerminals,
  })
  const terminalsQuery = useQuery({
    queryKey: ['terminals'],
    queryFn: () => api.get<TerminalDto[]>('/terminals'),
    enabled: canEditTerminals,
  })

  const users = usersQuery.data ?? []
  const branchOptions = (restaurantQuery.data?.branches ?? []).map((b) => ({ id: b.id, name: b.name }))
  const terminalOptions = terminalsQuery.data ?? []

  const canPerformWrites = hasPasswordLogin
  const canOpenAccess = canEditBranches || canEditTerminals

  const filtered = useMemo(() => {
    let list = users
    if (roleFilter !== 'ALL') list = list.filter((u) => u.role === roleFilter)
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      list = list.filter(
        (u) =>
          u.displayName.toLowerCase().includes(q) ||
          u.username.toLowerCase().includes(q) ||
          (u.userCode ?? '').toLowerCase().includes(q),
      )
    }
    return list
  }, [users, roleFilter, search])

  if (usersQuery.isLoading) return <FullPageSpinner label="Loading staff…" />

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative max-w-xs flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search name, username, or code…"
            className="w-full rounded-lg border border-app bg-surface py-2 pl-9 pr-3 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <select
          value={roleFilter}
          onChange={(e) => setRoleFilter(e.target.value)}
          className="rounded-lg border border-app bg-surface px-3 py-2 text-sm text-app outline-none"
        >
          <option value="ALL">All Roles</option>
          {ROLE_NAMES.map((r) => (
            <option key={r} value={r}>
              {titleCase(r)}
            </option>
          ))}
        </select>
        <Button size="sm" variant="secondary" onClick={() => usersQuery.refetch()} title="Refresh">
          <RefreshCw className={cn('h-3.5 w-3.5', usersQuery.isFetching && 'animate-spin')} /> Refresh
        </Button>
        {canManageUsers && canPerformWrites && (
          <Button size="sm" onClick={() => setAddOpen(true)} className="ml-auto">
            <Plus className="h-3.5 w-3.5" /> Add Staff
          </Button>
        )}
      </div>

      {canManageUsers && !canPerformWrites && (
        <div className="flex items-start gap-2 rounded-lg border border-info/30 bg-info-soft px-3 py-2 text-xs text-info">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            You have permission to manage staff, but adding or editing an account requires signing in with a
            username and password, not a PIN. Sign in again from the Manager/Admin login screen.
          </span>
        </div>
      )}

      {usersQuery.isError ? (
        // Bistrodesk follow-up requirement #6 ("no user is displaying even. active user are
        // there."): this used to fall straight into the "no staff members match these filters"
        // empty state below on ANY fetch failure (permission error, network issue, a transient
        // 500) - `users = usersQuery.data ?? []` silently defaults to an empty roster with no sign
        // anything went wrong, which reads exactly like "there really are zero users" even though
        // the account correctly has real, active staff. Surfacing the actual error (same pattern
        // CustomersPage.tsx's customersQuery.isError already uses) turns a confusing "empty roster"
        // into an actionable message - and if the underlying failure turns out to be a genuine
        // backend bug, its real error code/message is now visible instead of hidden.
        <div className="flex h-64 flex-col items-center justify-center gap-2 text-center text-muted">
          <AlertTriangle className="h-8 w-8 text-danger" />
          <span className="text-sm font-medium text-danger">
            {usersQuery.error instanceof ApiError ? usersQuery.error.message : 'Could not load staff members'}
          </span>
          <Button size="sm" variant="secondary" onClick={() => usersQuery.refetch()}>
            <RefreshCw className="h-3.5 w-3.5" /> Try again
          </Button>
        </div>
      ) : filtered.length === 0 ? (
        <div className="flex h-64 flex-col items-center justify-center gap-2 text-muted">
          <Users2 className="h-8 w-8" />
          <span className="text-sm font-medium">
            {users.length === 0 ? 'No staff members found' : 'No staff members match these filters'}
          </span>
        </div>
      ) : (
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[820px] text-sm">
              <thead>
                <tr className="border-b border-app bg-app/40 text-left text-xs font-bold uppercase tracking-wide text-muted">
                  <th className="px-4 py-3">Name</th>
                  <th className="px-4 py-3">Username / Code</th>
                  <th className="px-4 py-3">Role</th>
                  <th className="px-4 py-3">Branches</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((u) => (
                  <tr key={u.id} className="border-b border-app last:border-0">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2 font-semibold text-app">
                        {u.displayName}
                        {u.id === currentUserId && <Badge tone="brand">You</Badge>}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-muted">
                      <div>@{u.username}</div>
                      {u.userCode && (
                        <span className="mt-0.5 inline-block rounded bg-app px-1.5 py-0.5 font-mono text-[10px] font-bold text-muted">
                          {u.userCode}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={ROLE_TONE[u.role] ?? 'neutral'}>{titleCase(u.role)}</Badge>
                    </td>
                    <td className="px-4 py-3 text-muted">
                      {u.branchNames.length > 0 ? u.branchNames.join(', ') : 'All branches'}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={u.active ? 'success' : 'danger'}>{u.active ? 'Active' : 'Inactive'}</Badge>
                      {u.hasPin && (
                        <span className="ml-1.5 inline-flex items-center gap-1 text-[11px] font-medium text-muted">
                          <KeyRound className="h-3 w-3" /> PIN
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap justify-end gap-1.5">
                        {canManageUsers && canPerformWrites ? (
                          <>
                            <Button size="sm" variant="secondary" onClick={() => setEditTarget(u)}>
                              <UserCog className="h-3.5 w-3.5" /> Edit
                            </Button>
                            <Button size="sm" variant="secondary" onClick={() => setPinTarget(u)} title="Change PIN">
                              <Lock className="h-3.5 w-3.5" />
                            </Button>
                          </>
                        ) : (
                          !canOpenAccess && <span className="text-xs text-muted">View only</span>
                        )}
                        {canOpenAccess && canPerformWrites && (
                          <Button size="sm" variant="secondary" onClick={() => setAccessTarget(u)} title="Branch/terminal access & login code">
                            <Building2 className="h-3.5 w-3.5" /> Access
                          </Button>
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

      {canManageUsers && canPerformWrites && <AddStaffModal open={addOpen} onClose={() => setAddOpen(false)} />}
      {canManageUsers && canPerformWrites && editTarget && <EditStaffModal user={editTarget} onClose={() => setEditTarget(null)} />}
      {canManageUsers && canPerformWrites && pinTarget && <ChangePinModal user={pinTarget} onClose={() => setPinTarget(null)} />}
      {canOpenAccess && canPerformWrites && accessTarget && (
        <AccessModal
          user={accessTarget}
          branchOptions={branchOptions}
          terminalOptions={terminalOptions}
          canEditUserCode={canManageUsers}
          canEditBranches={canEditBranches}
          canEditTerminals={canEditTerminals}
          onClose={() => setAccessTarget(null)}
        />
      )}
    </div>
  )
}

export function UsersPage() {
  const { hasPermission, hasPasswordLogin } = useAuthStore()
  const canViewUsers = hasPermission('USER_VIEW') || hasPermission('USER_MANAGE')
  const canManageUsers = hasPermission('USER_MANAGE')
  const canEditBranches = hasPermission('USER_MANAGE') || hasPermission('BRANCH_MANAGE')
  const canEditTerminals = hasPermission('USER_MANAGE') || hasPermission('TERMINAL_MANAGE')

  if (!canViewUsers) {
    return (
      <Card className="flex flex-col items-center justify-center gap-3 px-6 py-20 text-center">
        <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-danger-soft text-danger">
          <ShieldAlert className="h-7 w-7" />
        </div>
        <h2 className="text-lg font-bold text-app">Access Restricted</h2>
        <p className="max-w-sm text-sm text-muted">
          You don't have permission to view Staff. Ask an administrator to grant you access if you believe this is a
          mistake.
        </p>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      <div>
        <h2 className="flex items-center gap-2 text-lg font-bold text-app">
          <Users2 className="h-5 w-5 text-brand-600" /> Users &amp; Staff
        </h2>
        <p className="mt-0.5 text-sm text-muted">
          Manage this branch's staff accounts, login credentials, and branch/terminal access. Role and permission
          definitions are managed by Bistrodesk from the Admin console, not from here.
        </p>
      </div>

      <StaffDirectory
        canManageUsers={canManageUsers}
        canEditBranches={canEditBranches}
        canEditTerminals={canEditTerminals}
        hasPasswordLogin={hasPasswordLogin()}
      />
    </div>
  )
}
