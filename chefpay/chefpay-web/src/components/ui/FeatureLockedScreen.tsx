import { ShieldAlert } from 'lucide-react'

import { Card } from '@/components/ui/Card'

/**
 * Bistrodesk branch-isolation release (requirement #5's confirmed enforcement gap): the whole-
 * screen "not included in your plan" block, extracted from `PurchaseOrdersPage`'s own inline
 * version (the only screen that had one before this release) so every other gated screen this
 * release adds - Inventory, Reservations, the AI screens, Appearance's branding controls - renders
 * the exact same look rather than a bespoke one hand-copied per file. Mirrors this app's existing
 * "explain why, don't silently hide" convention ({@link LockedFeatureChip} does the same for a
 * single control) - a locked screen tells the viewer exactly what's missing and how to fix it,
 * rather than 404ing or rendering blank.
 */
export function FeatureLockedScreen({ title, message }: { title: string; message: string }) {
  return (
    <Card className="flex flex-col items-center justify-center gap-3 px-6 py-20 text-center">
      <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-warning-soft text-warning">
        <ShieldAlert className="h-7 w-7" />
      </div>
      <h2 className="text-lg font-bold text-app">{title}</h2>
      <p className="max-w-sm text-sm text-muted">{message}</p>
    </Card>
  )
}
