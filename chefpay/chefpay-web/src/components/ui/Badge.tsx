import type { HTMLAttributes } from 'react'

import { cn } from '@/lib/utils'

type Tone = 'neutral' | 'success' | 'danger' | 'warning' | 'info' | 'brand'

const toneClasses: Record<Tone, string> = {
  neutral: 'bg-app text-muted',
  success: 'bg-success-soft text-success',
  danger: 'bg-danger-soft text-danger',
  warning: 'bg-warning-soft text-warning',
  info: 'bg-info-soft text-info',
  brand: 'bg-brand-100 text-brand-700 dark:bg-brand-900 dark:text-brand-200',
}

interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: Tone
}

export function Badge({ className, tone = 'neutral', ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2.5 py-0.5 text-[11px] font-bold uppercase tracking-wide',
        toneClasses[tone],
        className,
      )}
      {...props}
    />
  )
}
