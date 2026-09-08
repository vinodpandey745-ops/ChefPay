import { clearCache } from './offlineDb'

const STORAGE_KEY = 'chefpay_terminal_identity_v1'

/**
 * Round 17: "this browser IS this terminal" identity, persisted separately from the staff session
 * (`chefpay_web_session_v1` in `store/auth.ts`) precisely so it survives one staff member signing
 * out and another signing in on the same physical device - a shared counter tablet keeps being
 * "Counter 1" across every shift regardless of who's logged in.
 *
 * `terminalCode` is never chosen by the user - it's assigned by the server (`Device#terminalCode`,
 * e.g. "T-4F2A") the first time this browser successfully logs in, and persisted here afterward so
 * every subsequent login re-identifies the SAME `Device` row (see `LoginRequest#terminalCode`'s
 * javadoc) instead of the server registering a new one.
 *
 * Phase 2: the one-time first-run flow (`LoginPage`'s `PosFirstRunFlow`) now collects the real
 * Branch Code -> Terminal Select sequence instead of a freely-typed name, so `terminalId` (the
 * exact `Device` row id, known directly from the public `GET /branches/{id}/terminals` list) is
 * persisted alongside `terminalCode` and is what every login now sends (preferred by the server
 * over the terminalCode/deviceName heuristics - see `LoginRequest#terminalId`'s javadoc).
 * `terminalName` is populated from that same selection (display only, matches whatever the admin
 * named the terminal from Branches & Terminals) rather than user-typed free text.
 *
 * `deviceSuffix` and `branchName` are practical additions beyond the spec's minimum three fields:
 * - `deviceSuffix`: a short stable random tag appended to the friendly `deviceName` sent at login
 *   (e.g. "Counter 1 (K3F9)"), kept only as a defense-in-depth fallback for the rare case a client
 *   sends no `terminalId`/`terminalCode` at all (an old cached identity) and the server falls back
 *   to its by-name heuristic. Generated once, on the first `saveTerminalIdentity` call.
 * - `branchName`: display-only convenience so the login screen can show "Branch: <name>" without
 *   needing an extra fetch just to render a badge.
 */
export interface TerminalIdentity {
  terminalCode: string | null
  /** Phase 2: the exact Device row id this browser is registered as - see the interface javadoc
   * above. Null until Terminal Select has actually picked (or auto-picked) one. */
  terminalId: string | null
  terminalName: string
  branchId: string | null
  /** Display-only; never sent to the server. Null until a login response has told us. */
  branchName: string | null
  deviceSuffix: string
}

const BLANK_IDENTITY: TerminalIdentity = {
  terminalCode: null,
  terminalId: null,
  terminalName: '',
  branchId: null,
  branchName: null,
  deviceSuffix: '',
}

function randomSuffix(): string {
  return Math.random().toString(36).slice(2, 8).toUpperCase()
}

export function getTerminalIdentity(): TerminalIdentity {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...BLANK_IDENTITY }
    const parsed = JSON.parse(raw) as Partial<TerminalIdentity>
    return {
      terminalCode: parsed.terminalCode ?? null,
      terminalId: parsed.terminalId ?? null,
      terminalName: parsed.terminalName ?? '',
      branchId: parsed.branchId ?? null,
      branchName: parsed.branchName ?? null,
      deviceSuffix: parsed.deviceSuffix ?? '',
    }
  } catch {
    // Private-browsing / storage-disabled - fall back to a blank identity rather than crashing;
    // the terminal just re-runs setup and registers as a "new" Device, same safe degradation the
    // session store (`store/auth.ts`) already uses for its own localStorage access.
    return { ...BLANK_IDENTITY }
  }
}

/** Merges `partial` onto the currently-stored identity and persists the result. Assigns a
 * `deviceSuffix` the first time this is ever called (Terminal Setup submit) if one doesn't already
 * exist, so it stays stable for the life of this browser's identity. */
export function saveTerminalIdentity(partial: Partial<TerminalIdentity>): TerminalIdentity {
  const current = getTerminalIdentity()
  const next: TerminalIdentity = {
    ...current,
    ...partial,
    deviceSuffix: current.deviceSuffix || randomSuffix(),
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    // Same safe degradation as getTerminalIdentity above - identity just won't survive a refresh.
  }
  return next
}

/** True once this browser has completed the one-time first-run flow: Branch Code entry -> Terminal
 * Select (see `LoginPage`'s `PosFirstRunFlow`) has resolved both a branch and an exact terminal.
 * `terminalCode` deliberately isn't part of this check - it's only ever assigned by the server
 * (on first login for a brand-new Device row), never chosen locally. */
export function hasCompletedTerminalSetup(): boolean {
  const identity = getTerminalIdentity()
  return identity.branchId !== null && identity.terminalId !== null
}

/** Resets this browser back to "never set up" - used by the "Not this terminal?" escape hatch on
 * the login screen so a device physically moved to a different branch (or handed to a different
 * till) can redo Branch Code entry instead of being stuck re-sending its old branch/terminal
 * forever. Deliberately keeps `deviceSuffix` stable (see the interface javadoc) rather than
 * generating a new one - it identifies this BROWSER, not this branch/terminal choice.
 *
 * Bistrodesk branch-isolation release (confirmed offline-scope decision: extend the existing
 * warm-cache mechanism, which includes fixing this real gap in it): a rebind is exactly the moment
 * this browser's offline cache (`lib/offlineDb.ts`'s IndexedDB `cache` store, warmed per-branch by
 * `lib/syncCore.ts#refreshCoreCaches` once this Phase's fix lands) can otherwise keep serving a
 * DIFFERENT branch's stale bare-and-branch-scoped GET responses forever - nothing else ever clears
 * it. Fire-and-forget (this function's own callers, and its return type, all predate promises and
 * stay synchronous) - a failed clear here is the same safe "just not offline-optimized this once"
 * degradation `offlineDb.ts` already applies to every other cache operation. */
export function clearTerminalIdentity(): TerminalIdentity {
  void clearCache()
  return saveTerminalIdentity({
    terminalCode: null,
    terminalId: null,
    terminalName: '',
    branchId: null,
    branchName: null,
  })
}

/** Simple viewport-width heuristic distinguishing the two web-client `DeviceType` values
 * (`WEB_TABLET`/`WEB_MOBILE`) - matches the breakpoint this app's own Tailwind config treats as
 * "mobile" elsewhere in chefpay-web. */
export function detectDeviceType(): 'WEB_TABLET' | 'WEB_MOBILE' {
  return window.innerWidth < 768 ? 'WEB_MOBILE' : 'WEB_TABLET'
}
