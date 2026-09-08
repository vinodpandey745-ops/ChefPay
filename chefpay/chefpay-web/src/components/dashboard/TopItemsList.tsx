import { formatCurrency } from '@/lib/utils'
import type { TopItem } from '@/types/api'

/** A ranked top-N list reads better as a list than a chart - the "job" here is ranking + exact
 * figures, not visual magnitude comparison, so this follows the dataviz skill's "sometimes the
 * answer is not a chart" guidance instead of forcing a bar chart. */
export function TopItemsList({ items }: { items: TopItem[] }) {
  if (!items.length) {
    return <div className="flex h-40 items-center justify-center text-sm text-muted">No items sold yet</div>
  }

  const maxRevenue = Math.max(...items.map((i) => i.revenue), 1)

  return (
    <ul className="space-y-3">
      {items.slice(0, 6).map((item, i) => (
        <li key={item.itemName} className="flex items-center gap-3">
          <span className="w-5 shrink-0 text-xs font-bold text-muted">{i + 1}</span>
          <div className="min-w-0 flex-1">
            <div className="flex items-baseline justify-between gap-2">
              <span className="truncate text-sm font-medium text-app">{item.itemName}</span>
              <span className="shrink-0 text-sm font-semibold text-app">{formatCurrency(item.revenue)}</span>
            </div>
            <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-app">
              <div
                className="h-full rounded-full bg-brand-500"
                style={{ width: `${Math.max((item.revenue / maxRevenue) * 100, 4)}%` }}
              />
            </div>
            <div className="mt-0.5 text-xs text-muted">{item.quantitySold} sold</div>
          </div>
        </li>
      ))}
    </ul>
  )
}
