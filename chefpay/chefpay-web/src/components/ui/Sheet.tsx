import { X } from 'lucide-react'
import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

interface SheetProps {
  open: boolean
  onClose: () => void
  title: string
  subtitle?: string
  children: ReactNode
  widthClassName?: string
}

/** Right-edge slide-over panel - used for the Tables page's "Quick Actions" panel (directly modeled
 * on the reference POS's table quick-actions slide-over: an order snapshot up top, contextual
 * actions below, all without leaving the Tables screen). Generic enough to reuse anywhere else a
 * panel-over-content pattern beats a full page navigation or a centered modal. */
export function Sheet({ open, onClose, title, subtitle, children, widthClassName = 'max-w-md' }: SheetProps) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-black/40 backdrop-blur-[1px]" onClick={onClose} aria-hidden="true" />
      <div
        className={cn('relative flex h-full w-full flex-col bg-surface shadow-2xl sheet-panel', widthClassName)}
        role="dialog"
        aria-modal="true"
      >
        <div className="flex items-start justify-between gap-3 border-b border-app px-5 py-4">
          <div className="min-w-0">
            <h2 className="truncate text-base font-bold text-app">{title}</h2>
            {subtitle && <p className="mt-0.5 truncate text-xs text-muted">{subtitle}</p>}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="shrink-0 rounded-lg p-1.5 text-muted hover:bg-app"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
      </div>
    </div>
  )
}
