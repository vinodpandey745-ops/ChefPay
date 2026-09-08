/**
 * Main-thread offline-sync orchestration. Sits on top of offlineDb.ts (storage) and syncCore.ts
 * (the actual replay/refresh logic, shared verbatim with the background worker - see
 * workers/sync.worker.ts's top comment for the full "why a worker, and why it drains itself"
 * writeup). This file owns:
 *
 *   - `syncNow()` - an immediate, main-thread sync pass. Used by the "Sync Now" button
 *     (DataSyncPanel.tsx) and fired automatically the instant the browser regains connectivity.
 *   - `startAutoSync()` / `stopAutoSync()` - creates/tears down the dedicated sync.worker.ts
 *     Worker that performs the recurring 30-minute background auto-sync, and relays the bearer
 *     token into it (a Worker can't read localStorage/zustand state itself).
 *
 * `syncNow()` runs on the main thread deliberately: it's a short, one-off, user-anticipated
 * operation (a manual click, or a single reconnect pass), where a brief burst of fetches is an
 * acceptable trade for simplicity. The recurring 30-minute pass is the one the task requires to
 * never hamper the POS, and that one runs inside the Worker instead.
 */
import { useAuthStore } from '@/store/auth'
import { useSyncStore } from '@/store/sync'

import { countOutbox } from './offlineDb'
import { drainOutbox, refreshCoreCaches } from './syncCore'

let worker: Worker | null = null
let listenersAttached = false
let unsubscribeAuth: (() => void) | null = null

async function refreshCounts(): Promise<void> {
  const counts = await countOutbox()
  useSyncStore.getState().setCounts(counts)
}

/** Bistrodesk branch-isolation release: this terminal's own bound branch (same resolution every
 * POS-facing screen already uses - `terminal?.branchId ?? defaultBranchId`, see
 * `PosTerminalPage`/`TablesPage`'s own javadocs) - not the signed-in user's whole accessible-branch
 * set, since the warm cache exists to serve THIS physical terminal offline, not every branch a
 * manager account happens to have access to. */
function currentBranchId(): string | null {
  const auth = useAuthStore.getState()
  return auth.terminal?.branchId ?? auth.defaultBranchId ?? null
}

/** Offline-order-creation fix: applies drainOutbox's `remapped` pairs to useSyncStore -
 * PosTerminalPage (the only place a `local-...` placeholder id can currently be showing) watches
 * `offlineOrderRemap` and redirects itself the moment its own placeholder appears here. Shared by
 * both the main-thread `syncNow()` below and `handleWorkerMessage` (the background worker's own
 * drain pass), so the two apply remaps identically. */
function applyRemaps(remapped: { localId: string; realId: string }[]): void {
  for (const { localId, realId } of remapped) {
    useSyncStore.getState().applyOfflineOrderRemap(localId, realId)
  }
}

export async function syncNow(): Promise<{ succeeded: number; failed: number }> {
  const store = useSyncStore.getState()
  if (store.syncing) return { succeeded: 0, failed: 0 }
  store.setSyncing(true)
  try {
    const token = useAuthStore.getState().token ?? null
    const result = await drainOutbox(token)
    applyRemaps(result.remapped)
    await refreshCoreCaches(token, currentBranchId())
    await refreshCounts()
    useSyncStore.getState().recordSync()
    return { succeeded: result.succeeded, failed: result.failed }
  } finally {
    useSyncStore.getState().setSyncing(false)
  }
}

function postTokenToWorker() {
  worker?.postMessage({
    type: 'auth-token',
    token: useAuthStore.getState().token ?? null,
    branchId: currentBranchId(),
  })
}

function handleWorkerMessage(event: MessageEvent) {
  const message = event.data as
    | {
        type: 'sync-result'
        succeeded: number
        failed: number
        parked: number
        remapped: { localId: string; realId: string }[]
        syncedAt: number
      }
    | { type: 'sync-skipped'; reason: string }
    | { type: 'sync-error'; message: string }
    | undefined

  if (message?.type === 'sync-result') {
    applyRemaps(message.remapped)
    useSyncStore.getState().recordSync()
    void refreshCounts()
  }
  // 'sync-skipped' (no token yet) and 'sync-error' (a single failed background tick) aren't
  // individually actionable - the pending/failed counts (refreshed here and on every syncNow())
  // are what the UI actually surfaces, and those stay accurate regardless.
}

function handleOnline() {
  useSyncStore.getState().setOnline(true)
  void syncNow()
}

function handleOffline() {
  useSyncStore.getState().setOnline(false)
}

/** Idempotent - safe to call more than once (e.g. React StrictMode double-invoking an effect).
 * Creates the background worker, seeds it with the current auth token, and wires up the listeners
 * that keep both the worker's token and the online/offline + outbox counts in useSyncStore fresh. */
export function startAutoSync(): void {
  if (typeof Worker !== 'undefined' && !worker) {
    worker = new Worker(new URL('../workers/sync.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = handleWorkerMessage
    postTokenToWorker()
  }

  if (!listenersAttached) {
    listenersAttached = true
    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)
    unsubscribeAuth = useAuthStore.subscribe(postTokenToWorker)
  }

  useSyncStore.getState().setOnline(navigator.onLine)
  void refreshCounts()
}

export function stopAutoSync(): void {
  worker?.terminate()
  worker = null

  if (listenersAttached) {
    window.removeEventListener('online', handleOnline)
    window.removeEventListener('offline', handleOffline)
    unsubscribeAuth?.()
    unsubscribeAuth = null
    listenersAttached = false
  }
}
