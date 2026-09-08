import { Lock } from 'lucide-react'

import { cn } from '@/lib/utils'

/** Phase 2 item 24: renders a feature code as a small chip - unlocked ones plain, locked ones with
 * a lock icon, muted/disabled styling, and a "available in the X plan" tooltip/caption - rather
 * than hiding the feature outright. One shared component so a newly-gated feature never needs a
 * bespoke locked-state UI hand-written per screen (see `hooks/useEntitlements.ts`'s own javadoc). */
export function LockedFeatureChip({
  label,
  enabled,
  planHint,
}: {
  label: string
  enabled: boolean
  planHint?: string
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold',
        enabled ? 'bg-success-soft text-success' : 'bg-app text-muted opacity-70',
      )}
      title={enabled ? undefined : planHint ? `Available in the ${planHint} plan` : 'Not included in your current plan'}
    >
      {!enabled && <Lock className="h-3 w-3 shrink-0" />}
      {label}
    </span>
  )
}
