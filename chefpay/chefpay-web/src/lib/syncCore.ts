/**
 * The actual outbox-replay + core-cache-refresh logic - factored out of syncEngine.ts specifically
 * so it can be imported, unmodified, from BOTH the main thread (syncEngine.ts's manual `syncNow()`)
 * and the dedicated `sync.worker.ts` background thread (see that file's top comment for why the
 * worker does its own draining instead of just being a 30-minute alarm). Like offlineDb.ts, this
 * module has no `window`-only dependency - it takes the bearer token as a plain argument instead of
 * reading it from the zustand auth store, and uses `self.location.origin` (present on both `window`
 * and a Worker's global scope) instead of `window.location.origin`.
 */
import { ApiError, type ApiEnvelope } from '@/types/api'

import {
  type OutboxEntry,
  type QueryParams,
  buildCacheKey,
  listOutbox,
  removeOutboxEntry,
  setCached,
  updateOutboxEntry,
} from './offlineDb'

/** Mirrors api.ts's `API_BASE` + `buildUrl` exactly. Duplicated rather than imported because api.ts
 * pulls in the zustand auth store, which reads localStorage - an API that a Worker's global scope
 * does not reliably provide. If api.ts's URL-building logic ever changes, mirror the change here. */
const API_BASE = '/api'

function buildUrl(path: string, query?: QueryParams): string {
  const url = new URL(API_BASE + path, self.location.origin)
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) url.searchParams.set(key, String(value))
    }
  }
  return url.pathname + url.search
}

function authHeaders(token: string | null): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

/** A small cap on retries per queued entry - once exceeded, the entry is "parked" (marked
 * `failed: true`) instead of retried forever, so a permanently-invalid mutation (e.g. a 409 the
 * server will never accept) doesn't spin silently on every sync pass. */
const MAX_ATTEMPTS = 5

/** Returns the replayed request's unwrapped response data (rather than discarding it like the
 * pre-offline-order-creation-fix version of this function did) - drainOutbox needs it to learn the
 * real server id a placeholder `/orders` create resolved to (see `createsLocalId` on OutboxEntry). */
async function replayEntry(entry: OutboxEntry, token: string | null): Promise<unknown> {
  const response = await fetch(buildUrl(entry.path, entry.query), {
    method: entry.method,
    headers: authHeaders(token),
    body: entry.body !== undefined ? JSON.stringify(entry.body) : undefined,
  })

  let envelope: ApiEnvelope<unknown> | null = null
  try {
    envelope = (await response.json()) as ApiEnvelope<unknown>
  } catch {
    // No JSON body - fall through to the generic error below, same as api.ts's apiRequest.
  }

  if (!response.ok || !envelope || envelope.success === false) {
    const message = envelope?.message ?? `Request failed (${response.status})`
    throw new ApiError(message, response.status, envelope?.errorCode ?? null)
  }

  return envelope.data
}

/** Local-placeholder-id -> real-server-id, learned as `/orders` creates queued while offline
 * (see OutboxEntry.createsLocalId) replay successfully. Module-scoped (not per-call) so a later
 * drain pass in the SAME tab/worker lifetime can still resolve a placeholder segment on an entry
 * that failed to replay (for an unrelated, transient reason) in the same pass the create entry
 * itself succeeded and was removed from the outbox - see the "in-memory is fine" note in the
 * offline-order-creation fix plan. Known limitation: a full page reload or worker restart between
 * the create succeeding and a dependent entry replaying loses this mapping, since by then the
 * create entry is gone from the outbox and there's nowhere else to recover the real id from until
 * that dependent entry itself gets a chance to be inspected again - at that point it will simply
 * keep failing (409/404 against a literal "local-..." id) until parked, surfaced via the existing
 * "N failed to sync" badge rather than silently lost. */
const localIdMap = new Map<string, string>()

/** Rewrites every occurrence of a known placeholder id in `path` to its real server id, once that
 * mapping is known (see `localIdMap`) - e.g. `/orders/local-abc/items` -> `/orders/<realId>/items`.
 * Returns `path` unchanged (same string instance) when nothing matches, so a normal entry with no
 * local-id reference is byte-for-byte unaffected. */
function remapLocalIdsInPath(path: string): string {
  let result = path
  for (const [localId, realId] of localIdMap) {
    if (result.includes(localId)) result = result.split(localId).join(realId)
  }
  return result
}

export interface DrainResult {
  succeeded: number
  failed: number
  parked: number
  /** Offline-order-creation fix: every placeholder `/orders` create (see OutboxEntry.createsLocalId)
   * that successfully replayed during THIS drain pass, as {localId, realId} pairs. Callers
   * (syncEngine.ts's syncNow / sync.worker.ts's postMessage, relayed back through
   * syncEngine.ts's handleWorkerMessage) apply each pair to useSyncStore so any component still
   * showing the placeholder id can redirect to the real one and drop the stale cache entry. */
  remapped: { localId: string; realId: string }[]
}

/** Drains the outbox strictly FIFO, one entry at a time (never in parallel - queued writes are
 * often causally dependent, e.g. "create order" then "add item to that order"). An entry that
 * fails is left in the outbox with `attempts` incremented for the next pass, unless it has now hit
 * MAX_ATTEMPTS, in which case it's parked (`failed: true`) so the UI can show "N changes failed to
 * sync" instead of retrying broken data forever. */
export async function drainOutbox(token: string | null): Promise<DrainResult> {
  const entries = await listOutbox()
  let succeeded = 0
  let failed = 0
  let parked = 0
  const remapped: { localId: string; realId: string }[] = []

  for (const entry of entries) {
    if (entry.failed) {
      parked++
      continue
    }
    // Offline-order-creation fix: rewrite a placeholder id segment (e.g. `local-<uuid>` in
    // `/orders/local-<uuid>/items`) to the real server id the moment it's known, WITHOUT mutating
    // the stored entry itself - an entry with no local-id reference gets the exact same `path`
    // back (see remapLocalIdsInPath), so this is a no-op for every already-working replay case.
    const effectivePath = remapLocalIdsInPath(entry.path)
    const effectiveEntry: OutboxEntry = effectivePath === entry.path ? entry : { ...entry, path: effectivePath }
    try {
      const data = await replayEntry(effectiveEntry, token)
      if (entry.createsLocalId && data && typeof data === 'object' && 'id' in data) {
        const realId = String((data as { id: unknown }).id)
        localIdMap.set(entry.createsLocalId, realId)
        remapped.push({ localId: entry.createsLocalId, realId })
      }
      await removeOutboxEntry(entry.id)
      succeeded++
    } catch (err) {
      const attempts = entry.attempts + 1
      const lastError = err instanceof Error ? err.message : 'Unknown error'
      if (attempts >= MAX_ATTEMPTS) {
        await updateOutboxEntry(entry.id, { attempts, lastError, failed: true })
        parked++
      } else {
        await updateOutboxEntry(entry.id, { attempts, lastError })
        failed++
      }
    }
  }

  return { succeeded, failed, parked, remapped }
}

/** A small, fixed set of GET endpoints worth keeping warm in the offline cache for a POS to stay
 * usable while the server is unreachable - deliberately NOT an attempt to cache every endpoint in
 * the app (out of scope). Refreshed after every successful sync pass (manual or background).
 *
 * Bistrodesk Phase 10 addition: `/customers` and `/billing/discounts` - tax config itself needs no
 * separate entry here (it lives on `Restaurant`/`Branch` fields returned by the already-warmed
 * `/restaurant`, not a standalone endpoint), but the guest directory and discount presets are their
 * own endpoints CustomersPage/DiscountRow call with no query params on their default/first load, so
 * warming the bare (no-query) response here gives those pages something to fall back to instead of
 * failing outright while offline. This does NOT help PosTerminalPage's phone-number customer LOOKUP
 * specifically - that always calls `/customers?query=<phone>`, a different cache key this bare entry
 * can't satisfy; true offline phone search would need a client-side filter fallback over this cached
 * list, which is a real, currently-open gap flagged in the final report rather than silently assumed
 * fixed by this cache-warming alone.
 *
 * Bistrodesk branch-isolation release (requirement: extend this existing mechanism, not build a new
 * one - see the plan's own "offline requirement" phase). `branchId`, when the caller supplies one
 * (this terminal's own bound branch - see `refreshCoreCaches`'s own javadoc), is passed ONLY to the
 * entries below whose real page-level query actually includes it, so the warmed cache key exactly
 * matches the key that page will look up when a genuine network error later falls back to the
 * cache (see `buildCacheKey` - a bare key and a `?branchId=...` key are different strings, so
 * warming the wrong one is silently useless, not merely redundant):
 *   - `/tables`: TablesPage.tsx / PosTerminalPage.tsx both call this WITH `branchId` whenever the
 *     terminal/user has one (Phase 2's own fix) - warming it bare, as this list previously did
 *     unconditionally, built a cache entry neither screen's real query would ever hit. This was a
 *     real, confirmed bug (not a hypothetical one) - not a partitioning nicety.
 *   - `/dashboard/summary`: added new this release - the one still-meaningful "reports summary" a
 *     manager would want available offline (see this constant's own resolved-ambiguity note below).
 * Every other entry's real call site never sends `branchId` at all (`CustomerController`/
 * `SupplierController` have no such query param - confirmed by reading both; every real
 * `/customers`/`/suppliers` call in chefpay-web is bare, or bare-plus-`query=<search>` for
 * `/customers`, itself a cache key this bare entry cannot help with either, same open gap as the
 * `/customers` note above), so passing branchId there would just create a second, dead cache entry
 * - kept bare-keyed exactly as before.
 *
 * Resolved ambiguity: the plan referred to warming "/reports summary" - there is no such endpoint.
 * `/reports/sales` and `/reports/branches` both require an arbitrary `from`/`to` date range with no
 * stable default worth guessing (whatever range ReportsPage's caller last had open), so warming them
 * would only ever coincidentally match a real lookup. `GET /dashboard/summary` is parameterless
 * beyond the optional `branchId` every Dashboard visit already sends and is the actual "at a glance"
 * summary screen most users see first, so it's the production-grade stand-in for "reports summary"
 * here; `/reports/*` stays out of scope for this cache. */
const BARE_CORE_GET_PATHS = ['/menu', '/restaurant', '/orders', '/customers', '/suppliers', '/billing/discounts'] as const
const BRANCH_SCOPED_CORE_GET_PATHS = ['/tables', '/dashboard/summary'] as const

/** `branchId` should be this terminal's own bound branch (`terminal?.branchId ?? defaultBranchId`,
 * the same resolution every POS-facing screen already uses - see `PosTerminalPage`/`TablesPage`'s
 * own javadocs) - omit it only for a caller with no such value yet (e.g. a terminal mid-first-run
 * setup), in which case the branch-scoped entries below are simply skipped rather than warmed with
 * a key no real query will ever produce. */
export async function refreshCoreCaches(token: string | null, branchId?: string | null): Promise<void> {
  const tasks: Array<{ path: string; query?: QueryParams }> = BARE_CORE_GET_PATHS.map((path) => ({ path }))
  if (branchId) {
    for (const path of BRANCH_SCOPED_CORE_GET_PATHS) {
      tasks.push({ path, query: { branchId } })
    }
  }

  await Promise.all(
    tasks.map(async ({ path, query }) => {
      try {
        const response = await fetch(buildUrl(path, query), { method: 'GET', headers: authHeaders(token) })
        if (!response.ok) return
        const envelope = (await response.json()) as ApiEnvelope<unknown>
        if (envelope.success && envelope.data !== null) {
          await setCached(buildCacheKey(path, query), envelope.data)
        }
      } catch {
        // Best-effort - a failed core-cache refresh should never break the rest of the sync pass.
      }
    }),
  )
}
