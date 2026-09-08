import { Building2, Download, Hash, KeyRound, MapPin, Monitor, Moon, ShieldCheck, Sun, User, Wifi, WifiOff } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate } from 'react-router-dom'

import { Button } from '@/components/ui/Button'
import { usePwaInstall, useOnlineStatus } from '@/hooks/usePwaInstall'
import { api } from '@/lib/api'
import {
  clearTerminalIdentity,
  detectDeviceType,
  getTerminalIdentity,
  hasCompletedTerminalSetup,
  saveTerminalIdentity,
} from '@/lib/terminalIdentity'
import type { TerminalIdentity } from '@/lib/terminalIdentity'
import {
  ApiError,
  type BranchByCodeResponse,
  type LoginRequest,
  type LoginResponse,
  type TerminalDto,
  type UpdateTerminalRequest,
} from '@/types/api'
import { useAppearanceStore } from '@/store/appearance'
import { useAuthStore } from '@/store/auth'
import { useThemeStore } from '@/store/theme'

/** Phase 2: the POS first-run flow (item 33's "POS FIRST RUN" diagram / Section E) - replaces the
 * old free-typed "name this terminal" screen with the real thing: a Branch Code (validated against
 * the PUBLIC `GET /branches/by-code/{code}`, a clear "no active branch found" message on any miss,
 * never a silent proceed) followed by Terminal Select (`GET /branches/{id}/terminals`, PUBLIC,
 * active terminals only) - auto-skipped when the branch has exactly one active terminal, matching
 * this app's existing "skip the picker if there's only one" precedent (see the old
 * `BranchesTerminalsPage` single-branch layout). Runs once per browser (see
 * `hasCompletedTerminalSetup`) - after this, only the credentials step below repeats each shift.
 *
 * <p>Fix (this round): a single-branch restaurant - the overwhelmingly common case for a
 * single-location install - previously still had to type a branch code with no way to discover it
 * from this screen, contradicting the design's own "a single-branch, single-terminal restaurant
 * never sees an extra screen" principle and the Terminal Select step's own auto-skip just below.
 * On mount this now silently tries the PUBLIC `GET /branches/default` (200 only when exactly one
 * active branch exists); on success it proceeds exactly as if that code had been typed, so a
 * single-branch install goes straight to Terminal Select (or straight through, if that branch also
 * has exactly one terminal) with no code entry at all. Any failure - zero or 2+ active branches, or
 * a network error - falls through to the manual code-entry screen below with no visible error, since
 * trying `/default` was never something the operator asked for. */
function PosFirstRunFlow({
  onComplete,
}: {
  onComplete: (identity: { branchId: string; branchName: string; terminal: TerminalDto }) => void
}) {
  const [checkingDefault, setCheckingDefault] = useState(true)
  const [step, setStep] = useState<'branch-code' | 'terminal-select'>('branch-code')
  const [branchCode, setBranchCode] = useState('')
  const [resolvedBranch, setResolvedBranch] = useState<{ branchId: string; branchName: string } | null>(null)
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function proceedWithBranch(branch: BranchByCodeResponse) {
    const activeTerminals = await api.get<TerminalDto[]>(`/branches/${branch.branchId}/terminals`)
    if (activeTerminals.length === 0) {
      setError(
        'This branch has no active terminals set up yet. Ask a manager to add one from Branches & Terminals, or try a different branch code.',
      )
      return
    }
    setResolvedBranch({ branchId: branch.branchId, branchName: branch.branchName })
    if (activeTerminals.length === 1) {
      onComplete({ branchId: branch.branchId, branchName: branch.branchName, terminal: activeTerminals[0] })
      return
    }
    setTerminals(activeTerminals)
    setStep('terminal-select')
  }

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const branch = await api.get<BranchByCodeResponse>('/branches/default')
        if (cancelled) return
        await proceedWithBranch(branch)
      } catch {
        // No single default branch (none yet, or 2+) - fall back to manual code entry, silently.
      } finally {
        if (!cancelled) setCheckingDefault(false)
      }
    })()
    return () => {
      cancelled = true
    }
    // Runs exactly once, on mount, regardless of later state changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function handleBranchCodeSubmit(event: FormEvent) {
    event.preventDefault()
    const code = branchCode.trim()
    if (!code) return
    setLoading(true)
    setError(null)
    try {
      const branch = await api.get<BranchByCodeResponse>(`/branches/by-code/${encodeURIComponent(code)}`)
      await proceedWithBranch(branch)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not check that branch code. Check your connection and try again.')
    } finally {
      setLoading(false)
    }
  }

  if (checkingDefault) {
    return (
      <div className="relative w-full max-w-sm">
        <div className="flex flex-col items-center gap-3 rounded-2xl border border-app bg-surface p-8 shadow-xl shadow-black/5">
          <div className="flex h-11 w-11 animate-pulse items-center justify-center rounded-xl bg-brand-600 text-white">
            <Building2 className="h-5 w-5" />
          </div>
          <p className="text-sm font-medium text-muted">Setting up this terminal…</p>
        </div>
      </div>
    )
  }

  if (step === 'terminal-select' && resolvedBranch) {
    return (
      <div className="relative w-full max-w-sm">
        <div className="rounded-2xl border border-app bg-surface p-6 shadow-xl shadow-black/5">
          <div className="mb-5 flex flex-col items-center gap-2 text-center">
            <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-brand-600 text-white">
              <Monitor className="h-5 w-5" />
            </div>
            <h1 className="text-lg font-bold text-app">Select This Terminal</h1>
            <p className="text-sm text-muted">
              <span className="font-semibold text-app">{resolvedBranch.branchName}</span> has more than one till -
              which one is this device?
            </p>
          </div>
          <div className="space-y-2">
            {terminals.map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => onComplete({ branchId: resolvedBranch.branchId, branchName: resolvedBranch.branchName, terminal: t })}
                className="flex w-full items-center justify-between rounded-xl border border-app px-3.5 py-2.5 text-left text-sm font-semibold text-app transition-colors hover:border-brand-500 hover:bg-brand-50 dark:hover:bg-brand-900/20"
              >
                <span className="flex items-center gap-2">
                  <Monitor className="h-4 w-4 text-muted" />
                  {t.name}
                </span>
                {t.terminalCode && (
                  <span className="rounded bg-app px-1.5 py-0.5 font-mono text-[10px] font-bold text-muted">
                    {t.terminalCode}
                  </span>
                )}
              </button>
            ))}
          </div>
          <button
            type="button"
            onClick={() => {
              setStep('branch-code')
              setResolvedBranch(null)
              setTerminals([])
            }}
            className="mt-4 w-full text-center text-xs font-semibold text-muted hover:text-app"
          >
            ← Use a different branch code
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="relative w-full max-w-sm">
      <div className="rounded-2xl border border-app bg-surface p-6 shadow-xl shadow-black/5">
        <div className="mb-5 flex flex-col items-center gap-2 text-center">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-brand-600 text-white">
            <Building2 className="h-5 w-5" />
          </div>
          <h1 className="text-lg font-bold text-app">Set Up This Terminal</h1>
          <p className="text-sm text-muted">
            Enter this branch's code (ask your manager if you don't have it). You only need to do this once - it's
            remembered on this device even after signing out.
          </p>
        </div>
        <form onSubmit={handleBranchCodeSubmit} className="space-y-4">
          <div>
            <label htmlFor="branch-code" className="mb-1.5 block text-xs font-semibold text-muted">
              Branch Code
            </label>
            <div className="relative">
              <Hash className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
              <input
                id="branch-code"
                type="text"
                autoFocus
                required
                autoComplete="off"
                value={branchCode}
                onChange={(e) => setBranchCode(e.target.value)}
                placeholder="e.g. 1100"
                className="w-full rounded-lg border border-app bg-app py-2 pl-9 pr-3 text-center text-lg font-semibold tracking-wide text-app outline-none focus:ring-2 focus:ring-brand-500"
              />
            </div>
          </div>
          {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-medium text-danger">{error}</div>}
          <Button type="submit" className="w-full" size="lg" disabled={!branchCode.trim() || loading}>
            {loading ? 'Checking…' : 'Continue'}
          </Button>
        </form>
      </div>
    </div>
  )
}

interface PendingBranchAssignment {
  terminalId: string
  version: number
  branches: LoginResponse['branches']
}

/** Round 17: shown once, right after a successful login, only when it's actually actionable - the
 * signed-in terminal has no branch yet AND the restaurant has 2+ branches to pick from AND this
 * user holds RESTAURANT_MANAGE. Phase 2 note: for any terminal that went through `PosFirstRunFlow`
 * above, `terminal.branchId` is already set by the time login succeeds, so this legacy screen is
 * now effectively dead-but-harmless for those terminals - kept only as defense-in-depth for an
 * older cached identity/admin session that skipped that flow. */
function AssignBranchScreen({
  pending,
  onDone,
  onSkip,
}: {
  pending: PendingBranchAssignment
  onDone: (branchId: string, branchName: string) => void
  onSkip: () => void
}) {
  const [branchId, setBranchId] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleAssign(event: FormEvent) {
    event.preventDefault()
    if (!branchId) return
    setSaving(true)
    setError(null)
    try {
      await api.put<TerminalDto>(`/terminals/${pending.terminalId}`, {
        branchId,
        version: pending.version,
      } satisfies UpdateTerminalRequest)
      const branch = pending.branches.find((b) => b.id === branchId)
      onDone(branchId, branch?.name ?? '')
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 409
          ? 'This terminal was changed elsewhere just now. Skip for now and assign it from Branches & Terminals instead.'
          : err instanceof ApiError
            ? err.message
            : 'Could not assign this terminal to a branch.',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="relative w-full max-w-sm">
      <div className="rounded-2xl border border-app bg-surface p-6 shadow-xl shadow-black/5">
        <div className="mb-5 flex flex-col items-center gap-2 text-center">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-brand-600 text-white">
            <Building2 className="h-5 w-5" />
          </div>
          <h1 className="text-lg font-bold text-app">Assign This Terminal</h1>
          <p className="text-sm text-muted">
            This organization has multiple branches. Choose which one this terminal is physically at, so staff can
            see it at login.
          </p>
        </div>
        <form onSubmit={handleAssign} className="space-y-4">
          <div>
            <label htmlFor="assign-branch" className="mb-1.5 block text-xs font-semibold text-muted">
              Branch
            </label>
            <select
              id="assign-branch"
              required
              value={branchId}
              onChange={(e) => setBranchId(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            >
              <option value="" disabled>
                Select a branch…
              </option>
              {pending.branches.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
            </select>
          </div>
          {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-medium text-danger">{error}</div>}
          <Button type="submit" className="w-full" size="lg" disabled={saving || !branchId}>
            {saving ? 'Assigning…' : 'Assign & Continue'}
          </Button>
          <button
            type="button"
            onClick={onSkip}
            className="w-full text-center text-xs font-semibold text-muted hover:text-app"
          >
            Skip for now
          </button>
        </form>
      </div>
    </div>
  )
}

/** `adminOnly` renders the dedicated `/staff-login` Manager/Admin route (Section E; renamed from
 * `/admin` in Bistrodesk Phase 12 - see App.tsx's route comment for why) - Username+Password
 * ONLY, the Quick PIN tab never rendered at all regardless of role, and the POS terminal first-run
 * flow (Branch Code -> Terminal Select) skipped entirely since a desk-based admin session isn't
 * tied to a physical till the same way a POS terminal is. The default (`adminOnly=false`, the
 * `/login` route every POS terminal actually uses) keeps both tabs, now defaulting to Quick PIN
 * (Phase 2's "User Code + PIN" is the new default login shape per the design doc), and gates on
 * the first-run flow above before either tab is shown. */
export function LoginPage({ adminOnly = false }: { adminOnly?: boolean }) {
  const { isAuthenticated, signIn } = useAuthStore()
  const { isDark, toggleTheme } = useThemeStore()
  const brandName = useAppearanceStore((s) => s.config.brandName)
  const logoDataUrl = useAppearanceStore((s) => s.config.logoDataUrl)
  const { canInstall, installed, promptInstall } = usePwaInstall()
  const online = useOnlineStatus()

  const [terminalSetupDone, setTerminalSetupDone] = useState(() => adminOnly || hasCompletedTerminalSetup())
  const [identity, setIdentity] = useState<TerminalIdentity>(() => getTerminalIdentity())
  const [pendingBranchAssignment, setPendingBranchAssignment] = useState<PendingBranchAssignment | null>(null)

  const [mode, setMode] = useState<'password' | 'pin'>(adminOnly ? 'password' : 'pin')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [userCode, setUserCode] = useState('')
  const [pin, setPin] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  // Only redirect once any one-time post-login step has been resolved - otherwise a fresh sign-in
  // that still needs branch assignment would be whisked to /dashboard before the user ever saw it.
  if (isAuthenticated && !pendingBranchAssignment) return <Navigate to="/dashboard" replace />

  if (!terminalSetupDone) {
    return (
      <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-app px-4 py-10">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute left-1/2 top-0 h-[36rem] w-[36rem] -translate-x-1/2 -translate-y-1/3 rounded-full bg-brand-500/20 blur-3xl"
        />
        <PosFirstRunFlow
          onComplete={({ branchId, branchName, terminal }) => {
            setIdentity(
              saveTerminalIdentity({
                branchId,
                branchName,
                terminalId: terminal.id,
                terminalCode: terminal.terminalCode,
                terminalName: terminal.name,
              }),
            )
            setTerminalSetupDone(true)
          }}
        />
      </div>
    )
  }

  if (pendingBranchAssignment) {
    return (
      <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-app px-4 py-10">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute left-1/2 top-0 h-[36rem] w-[36rem] -translate-x-1/2 -translate-y-1/3 rounded-full bg-brand-500/20 blur-3xl"
        />
        <AssignBranchScreen
          pending={pendingBranchAssignment}
          onDone={(branchId, branchName) => {
            setIdentity(saveTerminalIdentity({ branchId, branchName: branchName || null }))
            setPendingBranchAssignment(null)
          }}
          onSkip={() => setPendingBranchAssignment(null)}
        />
      </div>
    )
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const current = getTerminalIdentity()
      const deviceName = current.terminalName
        ? current.deviceSuffix
          ? `${current.terminalName} (${current.deviceSuffix})`
          : current.terminalName
        : undefined

      const body = {
        ...(mode === 'pin' ? { userCode, pin } : { username, password }),
        deviceName,
        deviceType: detectDeviceType(),
        terminalCode: current.terminalCode ?? undefined,
        terminalId: current.terminalId ?? undefined,
        branchId: current.branchId ?? undefined,
      } satisfies LoginRequest
      const response = await api.post<LoginResponse>('/auth/login', body)

      // Re-identify this browser as the SAME terminal on every future login, and remember the
      // branch it's now known to be at (if any) so the badge below and the next login both reflect
      // it without another round trip.
      const branchName = response.terminal.branchName ?? (response.terminal.branchId ? current.branchName : null)
      setIdentity(
        saveTerminalIdentity({
          terminalCode: response.terminal.terminalCode,
          branchId: response.terminal.branchId ?? current.branchId,
          branchName,
        }),
      )

      // Round 17 post-login branch assignment - see AssignBranchScreen's own comment: for a
      // Phase-2 terminal that went through PosFirstRunFlow, terminal.branchId is already set, so
      // this practically never fires any more; kept for the adminOnly/legacy-identity edge case.
      if (
        !adminOnly &&
        !response.terminal.branchId &&
        response.branches.length >= 2 &&
        response.permissions.includes('RESTAURANT_MANAGE')
      ) {
        try {
          const terminals = await api.get<TerminalDto[]>('/terminals')
          const mine = terminals.find((t) => t.id === response.terminal.id)
          if (mine) {
            signIn(response)
            setPendingBranchAssignment({ terminalId: mine.id, version: mine.version, branches: response.branches })
            return
          }
        } catch {
          // Non-critical lookup - fall through to a normal sign-in if it fails for any reason.
        }
      }

      signIn(response)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to sign in. Check your connection and try again.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-app px-4 py-10">
      {/* Soft brand-colored glow behind the card - purely decorative, gives the login screen the
          same "visually impressive" polish directive #5 asked for instead of a flat, empty page. */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute left-1/2 top-0 h-[36rem] w-[36rem] -translate-x-1/2 -translate-y-1/3 rounded-full bg-brand-500/20 blur-3xl"
      />

      <div className="absolute right-4 top-4 flex flex-col items-end gap-2 sm:right-6 sm:top-6">
        <div className="flex items-center gap-2">
          <div
            className={
              'flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-semibold ' +
              (online ? 'border-success/30 bg-success-soft text-success' : 'border-danger/30 bg-danger-soft text-danger')
            }
          >
            {online ? <Wifi className="h-3.5 w-3.5" /> : <WifiOff className="h-3.5 w-3.5" />}
            {online ? (adminOnly ? 'Online' : 'Terminal Online') : 'Offline'}
          </div>
          <button
            type="button"
            onClick={toggleTheme}
            className="rounded-full border border-app bg-surface p-2 text-muted hover:bg-app"
            aria-label="Toggle dark mode"
            title="Toggle dark mode"
          >
            {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          </button>
        </div>
        {/* Round 17: "this information will be shown at the time of login like in which branch or
            terminal you are login" - unobtrusive, matches the connectivity badge's shape/tone. Not
            shown on the admin route - a desk login isn't tied to a physical till. */}
        {!adminOnly && (
          <div className="flex items-center gap-1.5 rounded-full border border-app bg-surface px-3 py-1.5 text-xs font-medium text-muted">
            <Monitor className="h-3.5 w-3.5" />
            {identity.terminalName || 'Unnamed terminal'}
            {identity.branchName && (
              <>
                <span className="text-muted/50">·</span>
                <MapPin className="h-3.5 w-3.5" />
                {identity.branchName}
              </>
            )}
          </div>
        )}
      </div>

      <div className="relative w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center gap-3">
          {logoDataUrl ? (
            <img src={logoDataUrl} alt={brandName} className="h-14 w-14 rounded-2xl object-contain shadow-lg" />
          ) : (
            <svg width="56" height="56" viewBox="0 0 32 32" fill="none" aria-hidden="true" className="drop-shadow-lg">
              <rect width="32" height="32" rx="9" className="fill-brand-600" />
              <path d="M9 20.5c0-3.6 3.1-6.5 7-6.5s7 2.9 7 6.5" stroke="white" strokeWidth="2" strokeLinecap="round" />
              <circle cx="16" cy="10.5" r="2.5" fill="white" />
              <path d="M9 20.5h14" stroke="white" strokeWidth="2" strokeLinecap="round" />
            </svg>
          )}
          <div className="text-center">
            <h1 className="text-xl font-bold text-app">{brandName}</h1>
            <p className="text-sm text-muted">
              {adminOnly ? 'Manager & Admin sign-in' : 'Enter your PIN or credentials to sign in'}
            </p>
          </div>
        </div>

        <div className="rounded-2xl border border-app bg-surface p-6 shadow-xl shadow-black/5">
          {adminOnly && (
            <div className="mb-5 flex items-center gap-2 rounded-lg bg-brand-50 px-3 py-2 text-xs font-semibold text-brand-700 dark:bg-brand-900/30 dark:text-brand-200">
              <ShieldCheck className="h-3.5 w-3.5" /> Username &amp; password only - no Quick PIN on this screen.
            </div>
          )}
          {!adminOnly && (
            <div className="mb-5 flex rounded-lg bg-app p-1 text-sm font-medium">
              <button
                type="button"
                onClick={() => setMode('pin')}
                className={`flex flex-1 items-center justify-center gap-1.5 rounded-md py-1.5 transition-colors ${mode === 'pin' ? 'bg-surface text-app shadow-sm' : 'text-muted'}`}
              >
                <KeyRound className="h-3.5 w-3.5" /> User Code + PIN
              </button>
              <button
                type="button"
                onClick={() => setMode('password')}
                className={`flex flex-1 items-center justify-center gap-1.5 rounded-md py-1.5 transition-colors ${mode === 'password' ? 'bg-surface text-app shadow-sm' : 'text-muted'}`}
              >
                <User className="h-3.5 w-3.5" /> Credentials
              </button>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            {mode === 'password' ? (
              <>
                <div>
                  <label htmlFor="username" className="mb-1.5 block text-xs font-semibold text-muted">
                    Username
                  </label>
                  <div className="relative">
                    <User className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                    <input
                      id="username"
                      type="text"
                      autoComplete="username"
                      autoFocus
                      required
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      className="w-full rounded-lg border border-app bg-app py-2 pl-9 pr-3 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
                    />
                  </div>
                </div>
                <div>
                  <label htmlFor="password" className="mb-1.5 block text-xs font-semibold text-muted">
                    Password
                  </label>
                  <div className="relative">
                    <KeyRound className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                    <input
                      id="password"
                      type="password"
                      autoComplete="current-password"
                      required
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      className="w-full rounded-lg border border-app bg-app py-2 pl-9 pr-3 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
                    />
                  </div>
                </div>
              </>
            ) : (
              <>
                <div>
                  <label htmlFor="user-code" className="mb-1.5 block text-xs font-semibold text-muted">
                    User Code
                  </label>
                  <div className="relative">
                    <Hash className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                    <input
                      id="user-code"
                      type="text"
                      autoComplete="off"
                      autoFocus
                      required
                      value={userCode}
                      onChange={(e) => setUserCode(e.target.value.toUpperCase())}
                      placeholder="e.g. CASH001"
                      className="w-full rounded-lg border border-app bg-app py-2 pl-9 pr-3 text-sm uppercase tracking-wide text-app outline-none focus:ring-2 focus:ring-brand-500"
                    />
                  </div>
                </div>
                <div>
                  <label htmlFor="pin" className="mb-1.5 block text-xs font-semibold text-muted">
                    Staff PIN
                  </label>
                  <input
                    id="pin"
                    type="password"
                    inputMode="numeric"
                    autoComplete="off"
                    required
                    value={pin}
                    onChange={(e) => setPin(e.target.value)}
                    className="w-full rounded-lg border border-app bg-app px-3 py-2 text-center text-lg tracking-[0.5em] text-app outline-none focus:ring-2 focus:ring-brand-500"
                  />
                </div>
              </>
            )}

            {error && (
              <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-medium text-danger">{error}</div>
            )}

            <Button type="submit" className="w-full" size="lg" disabled={loading}>
              {loading ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>

          {!adminOnly && (
            <button
              type="button"
              onClick={() => {
                clearTerminalIdentity()
                setIdentity(getTerminalIdentity())
                setTerminalSetupDone(false)
              }}
              className="mt-4 w-full text-center text-xs font-semibold text-muted hover:text-app"
            >
              Not this terminal? Set up a different one
            </button>
          )}
        </div>

        <div className="mt-3 text-center text-xs">
          {adminOnly ? (
            <Link to="/login" className="font-semibold text-brand-600 hover:underline dark:text-brand-400">
              POS Terminal? Go to Terminal Sign-in →
            </Link>
          ) : (
            <Link to="/staff-login" className="font-semibold text-brand-600 hover:underline dark:text-brand-400">
              Manager or Admin? Sign in with your password →
            </Link>
          )}
        </div>

        {/* Directive #3 - a natural, functional continuation of the login flow rather than an
            afterthought: a genuine PWA install prompt (this app already ships a real manifest +
            service worker, so this actually installs a standalone app icon, not a fake link) plus
            an honest pointer to the real JavaFX desktop client for terminals that want a native app
            instead of a browser tab. Skipped on the admin route to keep that screen focused. */}
        {!adminOnly && !installed && (
          <div className="mt-4 flex items-center gap-3 rounded-2xl border border-brand-200 bg-brand-50 px-4 py-3.5 dark:border-brand-900/60 dark:bg-brand-900/20">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-brand-600 text-white">
              <Download className="h-5 w-5" />
            </div>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-bold text-app">Install {brandName} App</div>
              <p className="truncate text-xs text-muted">
                {canInstall ? 'Add a standalone app icon to this device.' : 'Use your browser’s install/add-to-home-screen option.'}
              </p>
            </div>
            {canInstall ? (
              <Button size="sm" variant="secondary" onClick={promptInstall} type="button">
                Install
              </Button>
            ) : (
              <span className="shrink-0 rounded-full bg-brand-100 px-2 py-1 text-[10px] font-bold uppercase tracking-wide text-brand-700 dark:bg-brand-900/50 dark:text-brand-200">
                PWA Ready
              </span>
            )}
          </div>
        )}

        {!adminOnly && (
          <div className="mt-3 flex items-center justify-center gap-1.5 text-xs text-muted">
            <Monitor className="h-3.5 w-3.5" />
            Also available as a native desktop app for front-of-house terminals — ask your admin.
          </div>
        )}
      </div>
    </div>
  )
}
