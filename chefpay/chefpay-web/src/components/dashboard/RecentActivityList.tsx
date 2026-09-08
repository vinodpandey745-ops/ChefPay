import { Badge } from '@/components/ui/Badge'
import { formatCurrency, formatDateTime } from '@/lib/utils'
import type { OrderDto } from '@/types/api'

const STATUS_TONE: Record<string, 'neutral' | 'success' | 'danger' | 'brand'> = {
  PAID: 'success',
  SERVED: 'brand',
  CANCELLED: 'danger',
  CLOSED: 'neutral',
}

/** Mirrors the reference dashboard's "Recent Activity" table - a quick operational pulse (order #,
 * type, table, total, status, time) without leaving the Dashboard, with a "View All" escape hatch
 * to the full Orders Log for anything needing filters/print/void. */
export function RecentActivityList({ orders }: { orders: OrderDto[] }) {
  if (!orders.length) {
    return <div className="flex h-32 items-center justify-center text-sm text-muted">No recent orders yet</div>
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[560px] text-left text-sm">
        <thead>
          <tr className="text-xs font-semibold uppercase tracking-wide text-muted">
            <th className="pb-2 pr-3">Order #</th>
            <th className="pb-2 pr-3">Type</th>
            <th className="pb-2 pr-3">Table</th>
            <th className="pb-2 pr-3 text-right">Total</th>
            <th className="pb-2 pr-3">Status</th>
            <th className="pb-2">Time</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((o) => (
            <tr key={o.id} className="border-t border-app">
              <td className="py-2 pr-3 font-semibold text-app">{o.orderNumber}</td>
              <td className="py-2 pr-3 text-muted">{o.orderType.replaceAll('_', ' ')}</td>
              <td className="py-2 pr-3 text-muted">{o.tableName ?? '—'}</td>
              <td className="py-2 pr-3 text-right font-semibold text-app">{formatCurrency(o.totalAmount)}</td>
              <td className="py-2 pr-3">
                <Badge tone={STATUS_TONE[o.status] ?? 'neutral'}>{o.status.replaceAll('_', ' ')}</Badge>
              </td>
              <td className="py-2 text-muted">{formatDateTime(o.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
