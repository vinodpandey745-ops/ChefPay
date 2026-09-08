import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** Standard shadcn-style class combiner: clsx for conditional classes, tailwind-merge to resolve
 * conflicting utility classes (e.g. a caller overriding a default `px-4` with `px-2`). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatCurrency(value: number | string | null | undefined, symbol = '₹'): string {
  if (value === null || value === undefined) return `${symbol}0.00`
  const n = typeof value === 'string' ? Number(value) : value
  if (Number.isNaN(n)) return `${symbol}0.00`
  return `${symbol}${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}
