import type { LucideIcon } from 'lucide-react'

import { Card, CardContent } from '@/components/ui/Card'
import { cn } from '@/lib/utils'

interface StatTileProps {
  label: string
  value: string
  icon: LucideIcon
  tone?: 'brand' | 'success' | 'warning' | 'danger' | 'info'
  hint?: string
  /** 0-100 - renders a slim progress bar under the hint (e.g. table occupancy). Omit for a plain tile. */
  progress?: number
}

const toneClasses: Record<NonNullable<StatTileProps['tone']>, string> = {
  brand: 'bg-brand-50 text-brand-600 dark:bg-brand-900/40 dark:text-brand-300',
  success: 'bg-success-soft text-success',
  warning: 'bg-warning-soft text-warning',
  danger: 'bg-danger-soft text-danger',
  info: 'bg-info-soft text-info',
}

const progressBarClasses: Record<NonNullable<StatTileProps['tone']>, string> = {
  brand: 'bg-brand-500',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-danger',
  info: 'bg-info',
}

export function StatTile({ label, value, icon: Icon, tone = 'brand', hint, progress }: StatTileProps) {
  return (
    <Card>
      <CardContent className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted">{label}</div>
          <div className="mt-1.5 truncate text-2xl font-bold text-app">{value}</div>
          {hint && <div className="mt-1 text-xs text-muted">{hint}</div>}
          {progress !== undefined && (
            <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-app">
              <div
                className={cn('h-full rounded-full', progressBarClasses[tone])}
                style={{ width: `${Math.min(100, Math.max(0, progress))}%` }}
              />
            </div>
          )}
        </div>
        <div className={cn('flex h-10 w-10 shrink-0 items-center justify-center rounded-xl', toneClasses[tone])}>
          <Icon className="h-5 w-5" />
        </div>
      </CardContent>
    </Card>
  )
}
