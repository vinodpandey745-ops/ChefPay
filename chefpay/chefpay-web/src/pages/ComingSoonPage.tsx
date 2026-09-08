import type { LucideIcon } from 'lucide-react'

import { Card } from '@/components/ui/Card'

interface ComingSoonPageProps {
  title: string
  description: string
  icon: LucideIcon
}

/** Placeholder for modules not yet ported to the new web client - kept honest rather than faked,
 * so nobody mistakes an empty screen for "this feature doesn't exist yet" vs "not built here yet". */
export function ComingSoonPage({ title, description, icon: Icon }: ComingSoonPageProps) {
  return (
    <Card className="flex flex-col items-center justify-center gap-3 px-6 py-20 text-center">
      <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-50 text-brand-600 dark:bg-brand-900/40 dark:text-brand-300">
        <Icon className="h-7 w-7" />
      </div>
      <h2 className="text-lg font-bold text-app">{title}</h2>
      <p className="max-w-sm text-sm text-muted">{description}</p>
      <p className="text-xs text-muted">This module is still on the JavaFX desktop client for now.</p>
    </Card>
  )
}
