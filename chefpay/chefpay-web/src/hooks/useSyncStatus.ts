import { useSyncStore } from '@/store/sync'

/** Thin wrapper around useSyncStore, mirroring how usePwaInstall/useOnlineStatus expose
 * ready-to-render state to components without them needing to know it's backed by Zustand. */
export function useSyncStatus() {
  const isOnline = useSyncStore((s) => s.isOnline)
  const lastSyncedAt = useSyncStore((s) => s.lastSyncedAt)
  const pendingCount = useSyncStore((s) => s.pendingCount)
  const failedCount = useSyncStore((s) => s.failedCount)
  const syncing = useSyncStore((s) => s.syncing)

  return { isOnline, lastSyncedAt, pendingCount, failedCount, syncing }
}
