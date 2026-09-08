import { Loader2 } from 'lucide-react'

import { cn } from '@/lib/utils'

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn('h-5 w-5 animate-spin text-brand-600', className)} aria-hidden="true" />
}

export function FullPageSpinner({ label }: { label?: string }) {
  return (
    <div className="flex h-full min-h-[240px] flex-col items-center justify-center gap-3 text-muted">
      <Spinner className="h-8 w-8" />
      {label && <span className="text-sm font-medium">{label}</span>}
    </div>
  )
}
