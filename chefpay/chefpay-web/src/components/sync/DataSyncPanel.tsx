import { AlertTriangle, CheckCircle2, CloudOff, Download, RefreshCw, Upload, Wifi, WifiOff } from 'lucide-react'
import { useRef, useState } from 'react'
import type { ChangeEvent } from 'react'

import { Button } from '@/components/ui/Button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/Card'
import { useSyncStatus } from '@/hooks/useSyncStatus'
import { exportAllData, importAllData } from '@/lib/offlineDb'
import { syncNow } from '@/lib/syncEngine'
import { cn } from '@/lib/utils'

type Notice = { kind: 'success' | 'error'; text: string } | null

/** Larger, self-contained "offline data & sync" panel meant to be dropped into a future Settings
 * tab. Card/Button-based per this app's existing UI primitives, and functional entirely on its
 * own - it reads live status via useSyncStatus() and drives syncNow()/exportAllData()/
 * importAllData() directly, so it renders something sensible even before anything else wires it
 * into a page. */
export function DataSyncPanel({ className }: { className?: string }) {
  const { isOnline, lastSyncedAt, pendingCount, failedCount, syncing } = useSyncStatus()
  const [notice, setNotice] = useState<Notice>(null)
  const [exporting, setExporting] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  async function handleSyncNow() {
    setNotice(null)
    try {
      const result = await syncNow()
      if (result.failed > 0) {
        setNotice({ kind: 'error', text: `Synced ${result.succeeded} change(s), ${result.failed} still pending retry.` })
      } else if (result.succeeded > 0) {
        setNotice({ kind: 'success', text: `Synced ${result.succeeded} queued change(s) successfully.` })
      } else {
        setNotice({ kind: 'success', text: 'Everything is already up to date.' })
      }
    } catch {
      setNotice({ kind: 'error', text: 'Sync failed - please try again once the connection is stable.' })
    }
  }

  async function handleDownloadBackup() {
    setNotice(null)
    setExporting(true)
    try {
      const payload = await exportAllData()
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const date = new Date().toISOString().slice(0, 10)
      const link = document.createElement('a')
      link.href = url
      link.download = `chefpay-backup-${date}.json`
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      setNotice({ kind: 'success', text: 'Backup downloaded.' })
    } catch {
      setNotice({ kind: 'error', text: 'Could not create a backup file.' })
    } finally {
      setExporting(false)
    }
  }

  function handleChooseRestoreFile() {
    fileInputRef.current?.click()
  }

  async function handleRestoreFileSelected(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = '' // allow re-selecting the same file later
    if (!file) return

    setNotice(null)
    setRestoring(true)
    try {
      const text = await file.text()
      const parsed = JSON.parse(text) as unknown
      await importAllData(parsed)
      setNotice({ kind: 'success', text: 'Backup restored. Offline data has been replaced with the backup.' })
    } catch (err) {
      setNotice({
        kind: 'error',
        text: err instanceof Error ? `Restore failed: ${err.message}` : 'Restore failed: invalid backup file.',
      })
    } finally {
      setRestoring(false)
    }
  }

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle>Offline Data & Sync</CardTitle>
        <div
          className={cn(
            'flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-semibold',
            isOnline ? 'border-success/30 bg-success-soft text-success' : 'border-danger/30 bg-danger-soft text-danger',
          )}
        >
          {isOnline ? <Wifi className="h-3.5 w-3.5" /> : <WifiOff className="h-3.5 w-3.5" />}
          {isOnline ? 'Terminal Online' : 'Terminal Offline'}
        </div>
      </CardHeader>

      <CardContent className="space-y-5">
        <p className="text-xs text-muted">
          This terminal keeps a local copy of your menu, tables and recent orders so the POS keeps working when the
          server can't be reached. Any changes made while offline are queued and sent automatically once the
          connection returns.
        </p>

        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatBlock label="Last synced" value={formatLastSynced(lastSyncedAt)} />
          <StatBlock label="Pending changes" value={String(pendingCount)} tone={pendingCount > 0 ? 'warning' : undefined} />
          <StatBlock label="Failed to sync" value={String(failedCount)} tone={failedCount > 0 ? 'danger' : undefined} />
          <StatBlock label="Status" value={syncing ? 'Syncing…' : isOnline ? 'Ready' : 'Waiting for connection'} />
        </dl>

        {notice && (
          <div
            className={cn(
              'flex items-start gap-2 rounded-xl border px-4 py-3 text-xs',
              notice.kind === 'success'
                ? 'border-success/30 bg-success-soft text-success'
                : 'border-warning/30 bg-warning-soft text-warning',
            )}
          >
            {notice.kind === 'success' ? (
              <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            ) : (
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            )}
            <span>{notice.text}</span>
          </div>
        )}

        {failedCount > 0 && !notice && (
          <div className="flex items-start gap-2 rounded-xl border border-warning/30 bg-warning-soft px-4 py-3 text-xs text-warning">
            <CloudOff className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <span>{failedCount} change(s) could not be synced after repeated attempts and have been parked - they will not retry automatically.</span>
          </div>
        )}

        <div className="flex flex-wrap gap-2">
          <Button type="button" size="sm" onClick={() => void handleSyncNow()} disabled={syncing || !isOnline}>
            <RefreshCw className={cn('h-4 w-4', syncing && 'animate-spin')} />
            Sync Now
          </Button>
          <Button type="button" size="sm" variant="secondary" onClick={() => void handleDownloadBackup()} disabled={exporting}>
            <Download className="h-4 w-4" />
            Download Backup (JSON)
          </Button>
          <Button type="button" size="sm" variant="secondary" onClick={handleChooseRestoreFile} disabled={restoring}>
            <Upload className="h-4 w-4" />
            Restore from Backup
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            accept="application/json,.json"
            className="hidden"
            onChange={(event) => void handleRestoreFileSelected(event)}
          />
        </div>
      </CardContent>
    </Card>
  )
}

function StatBlock({ label, value, tone }: { label: string; value: string; tone?: 'warning' | 'danger' }) {
  return (
    <div className="rounded-xl border border-app bg-app px-3 py-2">
      <dt className="text-[11px] font-medium uppercase tracking-wide text-muted">{label}</dt>
      <dd
        className={cn(
          'mt-0.5 text-sm font-semibold text-app',
          tone === 'warning' && 'text-warning',
          tone === 'danger' && 'text-danger',
        )}
      >
        {value}
      </dd>
    </div>
  )
}

function formatLastSynced(timestamp: number | null): string {
  if (!timestamp) return 'Never'
  const diffSeconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000))
  if (diffSeconds < 60) return 'Just now'
  const diffMinutes = Math.floor(diffSeconds / 60)
  if (diffMinutes < 60) return `${diffMinutes}m ago`
  const diffHours = Math.floor(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours}h ago`
  return new Date(timestamp).toLocaleString()
}
