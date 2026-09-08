/**
 * Dedicated background sync worker - a REAL separate OS thread (created via
 * `new Worker(new URL('./sync.worker.ts', import.meta.url), { type: 'module' })` in
 * lib/syncEngine.ts), not a main-thread `setInterval`. It ticks every 30 minutes and drains the
 * offline outbox itself, so replaying a large backlog of queued writes (e.g. accumulated overnight
 * while the server was unreachable) can never cause a dropped frame or input lag on the POS screen
 * mid-order - satisfying "a backend worker thread which should not hamper the POS" literally, not
 * just asynchronously.
 *
 * DESIGN CHOICE (option "a" vs "b" from the task spec): this worker performs the outbox-drain and
 * core-cache-refresh itself, rather than being a dumb timer that posts a `tick` and waits for the
 * main thread to do the work. Two reasons:
 *   1. Only this approach actually keeps the expensive part (the network round-trips and the
 *      read-modify-write loop over the outbox) off the main thread. A "dumb timer" would still run
 *      that loop on the main thread on every tick, which defeats the purpose for exactly the
 *      scenario that matters most - a big backlog built up while offline.
 *   2. IndexedDB is fully available inside a Worker's global scope, and lib/offlineDb.ts +
 *      lib/syncCore.ts have no `window`-only dependency (no localStorage, no zustand reads), so
 *      they import into this worker completely unmodified. That means there is exactly ONE
 *      implementation of "drain the outbox" / "refresh the core caches" - this worker and
 *      syncEngine.ts's manual `syncNow()` both call the very same functions - instead of two
 *      copies that could silently drift apart.
 *
 * The one thing this worker genuinely cannot do is read localStorage or the zustand auth store
 * directly, so the main thread pushes the current bearer token in via `postMessage` on startup and
 * whenever it changes (login/logout) - see syncEngine.ts's `postToken()`. Until a token has been
 * received at least once, a tick is a silent no-op: there's nothing useful to sync before the user
 * has ever logged in on this device, and attempting requests with no auth would just fail.
 */
import { drainOutbox, refreshCoreCaches } from '../lib/syncCore'

const THIRTY_MINUTES_MS = 30 * 60 * 1000

// Bistrodesk branch-isolation release: `branchId` rides along on the same 'auth-token' message the
// main thread already posts on startup and on every auth-store change (see syncEngine.ts's
// `postTokenToWorker`) - this terminal's own bound branch, needed so this worker's background
// `refreshCoreCaches` pass warms the same branch-scoped cache keys (`/tables`, `/dashboard/summary`)
// the real pages actually look up, exactly like the main thread's own `syncNow()`.
type InboundMessage = { type: 'auth-token'; token: string | null; branchId?: string | null } | { type: 'sync-now' }

type OutboundMessage =
  | {
      type: 'sync-result'
      succeeded: number
      failed: number
      parked: number
      // Offline-order-creation fix: DrainResult.remapped, relayed verbatim so syncEngine.ts's
      // handleWorkerMessage can apply it to useSyncStore exactly like the main-thread syncNow() does.
      remapped: { localId: string; realId: string }[]
      syncedAt: number
    }
  | { type: 'sync-skipped'; reason: 'no-token' }
  | { type: 'sync-error'; message: string }

let authToken: string | null = null
let branchId: string | null = null
let tickInFlight = false

async function runTick(): Promise<void> {
  if (tickInFlight) return // a manual "sync-now" arriving mid-scheduled-tick just no-ops
  if (!authToken) {
    self.postMessage({ type: 'sync-skipped', reason: 'no-token' } satisfies OutboundMessage)
    return
  }
  tickInFlight = true
  try {
    const result = await drainOutbox(authToken)
    await refreshCoreCaches(authToken, branchId)
    self.postMessage({ type: 'sync-result', ...result, syncedAt: Date.now() } satisfies OutboundMessage)
  } catch (err) {
    self.postMessage({
      type: 'sync-error',
      message: err instanceof Error ? err.message : 'Unknown sync error',
    } satisfies OutboundMessage)
  } finally {
    tickInFlight = false
  }
}

self.onmessage = (event: MessageEvent<InboundMessage>) => {
  const message = event.data
  if (message.type === 'auth-token') {
    authToken = message.token
    branchId = message.branchId ?? null
  } else if (message.type === 'sync-now') {
    void runTick()
  }
}

// The worker's entire lifetime IS the auto-sync schedule - syncEngine.ts creates it once (from
// startAutoSync()) and terminates it (stopAutoSync()) rather than starting/stopping ticks within
// a longer-lived worker, so it's correct to just start the interval as soon as this module loads.
setInterval(() => {
  void runTick()
}, THIRTY_MINUTES_MS)
