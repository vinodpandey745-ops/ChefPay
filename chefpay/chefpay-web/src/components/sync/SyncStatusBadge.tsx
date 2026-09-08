import { CloudOff, RefreshCw, Wifi, WifiOff } from 'lucide-react'
import { useEffect, useState } from 'react'

import { cn } from '@/lib/utils'
import { useSyncStatus } from '@/hooks/useSyncStatus'

/** Small presentational online/offline + sync-status pill, meant to be dropped into a
 * header/topbar. Reuses the same `border-success/30 bg-success-soft text-success` /
 * `border-danger/30 bg-danger-soft text-danger` tokens LoginPage.tsx's own "Terminal Online/Offline"
 * badge already established, so it reads as the same indicator continuing into the app shell rather
 * than a new visual language. No required props - self-contained, reads entirely from useSyncStatus(). */
export function SyncStatusBadge({ className }: { className?: string }) {
  const { isOnline, lastSyncedAt, pendingCount, failedCount, syncing } = useSyncStatus()
  const relativeTime = useRelativeTime(lastSyncedAt)

  const tone = !isOnline
    ? 'border-danger/30 bg-danger-soft text-danger'
    : failedCount > 0
      ? 'border-warning/30 bg-warning-soft text-warning'
      : 'border-success/30 bg-success-soft text-success'

  const icon = syncing ? (
    <RefreshCw className="h-3.5 w-3.5 animate-spin" />
  ) : !isOnline ? (
    <WifiOff className="h-3.5 w-3.5" />
  ) : failedCount > 0 ? (
    <CloudOff className="h-3.5 w-3.5" />
  ) : (
    <Wifi className="h-3.5 w-3.5" />
  )

  const label = syncing
    ? 'Syncing…'
    : !isOnline
      ? pendingCount > 0
        ? `Offline · ${pendingCount} pending`
        : 'Offline'
      : failedCount > 0
        ? `${failedCount} failed to sync`
        : pendingCount > 0
          ? `${pendingCount} pending`
          : relativeTime
            ? `Synced ${relativeTime}`
            : 'Online'

  return (
    <div
      className={cn(
        'flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-semibold',
        tone,
        className,
      )}
      title={lastSyncedAt ? `Last synced ${new Date(lastSyncedAt).toLocaleString()}` : 'Not synced yet'}
    >
      {icon}
      {label}
    </div>
  )
}

/** Re-renders on a 30s tick so "Synced 2m ago" keeps advancing without the parent needing to poll.
 * `Date.now()` is only ever read inside the effect (mount + interval), never inline during render,
 * so this stays a pure render given its current props/state. */
function useRelativeTime(timestamp: number | null): string | null {
  const [now, setNow] = useState<number | null>(null)
  useEffect(() => {
    setNow(Date.now())
    if (!timestamp) return
    const interval = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(interval)
  }, [timestamp])

  if (!timestamp || now === null) return null
  const diffSeconds = Math.max(0, Math.floor((now - timestamp) / 1000))
  if (diffSeconds < 60) return 'just now'
  const diffMinutes = Math.floor(diffSeconds / 60)
  if (diffMinutes < 60) return `${diffMinutes}m ago`
  const diffHours = Math.floor(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours}h ago`
  const diffDays = Math.floor(diffHours / 24)
  return `${diffDays}d ago`
}
