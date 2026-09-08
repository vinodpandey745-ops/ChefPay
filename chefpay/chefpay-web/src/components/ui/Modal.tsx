import { X } from 'lucide-react'
import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
  widthClassName?: string
}

export function Modal({ open, onClose, title, children, widthClassName = 'max-w-lg' }: ModalProps) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-[1px]" onClick={onClose} aria-hidden="true" />
      <div
        className={cn(
          'modal-panel relative flex max-h-[90vh] w-full flex-col rounded-2xl bg-surface shadow-2xl',
          widthClassName,
        )}
        role="dialog"
        aria-modal="true"
      >
        <div className="flex items-center justify-between gap-3 border-b border-app px-5 py-4">
          <h2 className="text-base font-bold text-app">{title}</h2>
          <button type="button" onClick={onClose} className="rounded-lg p-1.5 text-muted hover:bg-app" aria-label="Close">
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
      </div>
    </div>
  )
}
