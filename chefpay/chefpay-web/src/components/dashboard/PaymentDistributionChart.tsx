import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'

import { chartPalette } from '@/lib/chartColors'
import { formatCurrency } from '@/lib/utils'
import { useThemeStore } from '@/store/theme'
import type { PaymentMethodSales } from '@/types/api'

interface TooltipPayload {
  active?: boolean
  payload?: { name: string; value: number; payload: PaymentMethodSales }[]
}

function ChartTooltip({ active, payload }: TooltipPayload) {
  if (!active || !payload?.length) return null
  const entry = payload[0].payload
  return (
    <div className="rounded-lg border border-app bg-surface-raised px-3 py-2 text-xs shadow-md">
      <div className="font-semibold text-app">{entry.method}</div>
      <div className="text-muted">
        {formatCurrency(entry.total)} · {entry.count} payment{entry.count === 1 ? '' : 's'}
      </div>
    </div>
  )
}

export function PaymentDistributionChart({ data }: { data: PaymentMethodSales[] }) {
  const isDark = useThemeStore((s) => s.isDark)
  const palette = chartPalette(isDark)

  if (!data.length) {
    return <div className="flex h-64 items-center justify-center text-sm text-muted">No payments recorded yet</div>
  }

  // Fixed hue order per slot, never reassigned by rank/filter - a payment method always maps to
  // the same palette index it was first assigned (methods are stable/known, not user-filterable here).
  return (
    <ResponsiveContainer width="100%" height={260}>
      <PieChart>
        <Pie
          data={data}
          dataKey="total"
          nameKey="method"
          innerRadius={62}
          outerRadius={92}
          paddingAngle={2}
          cornerRadius={4}
          strokeWidth={0}
          label={(props: { method?: string; percent?: number }) =>
            props.percent && props.percent >= 0.08 ? `${props.method} ${Math.round(props.percent * 100)}%` : ''
          }
          labelLine={false}
        >
          {data.map((entry, i) => (
            <Cell key={entry.method} fill={palette[i % palette.length]} />
          ))}
        </Pie>
        <Tooltip content={<ChartTooltip />} />
        <Legend
          verticalAlign="bottom"
          height={32}
          formatter={(value: string) => <span className="text-xs text-muted">{value}</span>}
        />
      </PieChart>
    </ResponsiveContainer>
  )
}
