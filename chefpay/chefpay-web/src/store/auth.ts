import { create } from 'zustand'

import { clearCache } from '@/lib/offlineDb'
import type { LoginResponse } from '@/types/api'

const STORAGE_KEY = 'chefpay_web_session_v1'

interface StoredSession {
  token: string
  userId: string
  username: string
  displayName: string
  role: string
  permissions: string[]
  defaultBranchId: string | null
  /** Round 17: identity fields from LoginResponse - see Restaurant.java/Device.java's Round 17
   * javadocs for what these mean. Exposed the same way defaultBranchId already is, so other
   * screens (Sidebar/Topbar, wired up separately) can read "which org / branch / terminal" without
   * each re-deriving it from a raw LoginResponse themselves. */
  organizationId: string | null
  organizationName: string | null
  terminal: LoginResponse['terminal'] | null
  /** Phase 2: the deterministic login identifier and which login shape produced this session's
   * JWT - see LoginResponse's own javadoc. `loginMethod` gates every PW-only admin action in the
   * UI (Organization/Branches/Terminal bulk-create/most User writes) in addition to the server's
   * own `requirePasswordLogin()` check - see hasPasswordLogin() below. */
  userCode: string | null
  loginMethod: 'PASSWORD' | 'PIN' | null
}

interface AuthState extends Partial<StoredSession> {
  isAuthenticated: boolean
  signIn: (response: LoginResponse) => void
  signOut: () => void
  hasPermission: (code: string) => boolean
  /** True only for a session whose JWT came from username+password. Every Organization/Branch/
   * Terminal-bulk-create/most-User-write action requires this in addition to its permission -
   * mirrors the server's `AuthenticatedPrincipal#requirePasswordLogin()`, so the UI can hide/
   * disable an action it knows the server will reject, rather than only finding out on submit. */
  hasPasswordLogin: () => boolean
}

function loadSession(): StoredSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as StoredSession) : null
  } catch {
    return null
  }
}

function persistSession(session: StoredSession | null) {
  try {
    if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Private-browsing / storage-disabled - the session just won't survive a refresh, which is a
    // safe degradation rather than a crash.
  }
}

const existing = loadSession()

/** Session lives in localStorage, same convention as the existing /manager PWA (see that file's
 * own comment: this is a real deployed web page served from chefpay-server's own infrastructure,
 * not a sandboxed preview, so localStorage is the correct standard choice here). */
export const useAuthStore = create<AuthState>((set, get) => ({
  isAuthenticated: !!existing?.token,
  token: existing?.token,
  userId: existing?.userId,
  username: existing?.username,
  displayName: existing?.displayName,
  role: existing?.role,
  permissions: existing?.permissions ?? [],
  defaultBranchId: existing?.defaultBranchId ?? null,
  organizationId: existing?.organizationId ?? null,
  organizationName: existing?.organizationName ?? null,
  terminal: existing?.terminal ?? null,
  userCode: existing?.userCode ?? null,
  loginMethod: existing?.loginMethod ?? null,

  signIn: (response) => {
    const session: StoredSession = {
      token: response.token,
      userId: response.userId,
      username: response.username,
      displayName: response.displayName,
      role: response.role,
      permissions: response.permissions,
      defaultBranchId: response.defaultBranchId,
      organizationId: response.organizationId,
      organizationName: response.organizationName,
      terminal: response.terminal,
      userCode: response.userCode,
      loginMethod: response.loginMethod,
    }
    persistSession(session)
    set({ isAuthenticated: true, ...session })
  },

  signOut: () => {
    persistSession(null)
    // Bistrodesk branch-isolation release: a user/session change is the other real trigger (besides
    // a terminal rebind - see lib/terminalIdentity.ts#clearTerminalIdentity) for the offline warm
    // cache to otherwise keep serving stale, possibly different-branch data forever - nothing else
    // ever clears it. Fire-and-forget, same safe-degradation convention as offlineDb.ts's other
    // callers; signOut() itself stays synchronous.
    void clearCache()
    set({
      isAuthenticated: false,
      token: undefined,
      userId: undefined,
      username: undefined,
      displayName: undefined,
      role: undefined,
      permissions: [],
      defaultBranchId: null,
      organizationId: null,
      organizationName: null,
      terminal: null,
      userCode: null,
      loginMethod: null,
    })
  },

  hasPermission: (code: string) => {
    const permissions = get().permissions ?? []
    return permissions.includes(code)
  },

  hasPasswordLogin: () => get().loginMethod === 'PASSWORD',
}))
