import { create } from 'zustand'

/** Zustand store for offline-sync status, following the same convention as store/auth.ts and
 * store/theme.ts (actions live alongside state, called via `useSyncStore.getState().xxx()` from
 * non-component code like lib/api.ts and lib/syncEngine.ts, or via the hook from components). Not
 * persisted to localStorage on purpose - it's a live reflection of the IndexedDB outbox/online
 * state, recomputed from there on every sync pass and on startup, so it's never a stale source of
 * truth by itself. */
interface SyncState {
  isOnline: boolean
  lastSyncedAt: number | null
  pendingCount: number
  failedCount: number
  syncing: boolean

  /** Offline-order-taking fix (see PosTerminalPage.tsx): order ids (real server ids, or a
   * client-side `local-...` placeholder - see LOCAL_ORDER_PREFIX there) that currently have at
   * least one optimistic, not-yet-synced local edit applied to their cached OrderDto. Drives the
   * calm "Saved offline - will sync" cart indicator, distinct from the scarier red error banner
   * that's still shown for a REAL (reachable-server) failure. Not persisted, same rationale as the
   * rest of this store - it's a live reflection of in-flight local edits, not a durable record (the
   * durable one is the IndexedDB outbox itself; this is purely "should the UI show the calm badge
   * right now"). */
  pendingOfflineOrderIds: Set<string>
  /** Local-placeholder-id -> real-server-id, populated once syncCore.ts's drainOutbox successfully
   * replays the `/orders` create that placeholder stood in for (see DrainResult.remapped, consumed
   * by syncEngine.ts). A component still holding the placeholder id (PosTerminalPage's `orderId`
   * state) reads this once to redirect itself, then calls consumeOfflineOrderRemap to clear it -
   * this is a one-shot handoff, not a durable mapping table. */
  offlineOrderRemap: Record<string, string>

  setOnline: (isOnline: boolean) => void
  setSyncing: (syncing: boolean) => void
  setCounts: (counts: { pending: number; failed: number }) => void
  recordSync: () => void
  bumpPending: () => void
  markOrderPendingOffline: (orderId: string) => void
  clearOrderPendingOffline: (orderId: string) => void
  applyOfflineOrderRemap: (localId: string, realId: string) => void
  consumeOfflineOrderRemap: (localId: string) => string | undefined
}

export const useSyncStore = create<SyncState>((set, get) => ({
  isOnline: typeof navigator !== 'undefined' ? navigator.onLine : true,
  lastSyncedAt: null,
  pendingCount: 0,
  failedCount: 0,
  syncing: false,
  pendingOfflineOrderIds: new Set<string>(),
  offlineOrderRemap: {},

  setOnline: (isOnline) => set({ isOnline }),
  setSyncing: (syncing) => set({ syncing }),
  setCounts: ({ pending, failed }) => set({ pendingCount: pending, failedCount: failed }),
  recordSync: () => set({ lastSyncedAt: Date.now() }),
  bumpPending: () => set((state) => ({ pendingCount: state.pendingCount + 1 })),
  markOrderPendingOffline: (orderId) =>
    set((state) => {
      if (state.pendingOfflineOrderIds.has(orderId)) return state
      const next = new Set(state.pendingOfflineOrderIds)
      next.add(orderId)
      return { pendingOfflineOrderIds: next }
    }),
  clearOrderPendingOffline: (orderId) =>
    set((state) => {
      if (!state.pendingOfflineOrderIds.has(orderId)) return state
      const next = new Set(state.pendingOfflineOrderIds)
      next.delete(orderId)
      return { pendingOfflineOrderIds: next }
    }),
  applyOfflineOrderRemap: (localId, realId) =>
    set((state) => {
      const nextPending = new Set(state.pendingOfflineOrderIds)
      nextPending.delete(localId)
      return {
        pendingOfflineOrderIds: nextPending,
        offlineOrderRemap: { ...state.offlineOrderRemap, [localId]: realId },
      }
    }),
  consumeOfflineOrderRemap: (localId) => {
    const realId = get().offlineOrderRemap[localId]
    if (realId !== undefined) {
      set((state) => {
        const next = { ...state.offlineOrderRemap }
        delete next[localId]
        return { offlineOrderRemap: next }
      })
    }
    return realId
  },
}))
