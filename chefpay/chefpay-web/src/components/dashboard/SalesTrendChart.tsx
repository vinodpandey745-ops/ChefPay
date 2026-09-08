import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { formatCurrency } from '@/lib/utils'
import { SEQUENTIAL_DARK, SEQUENTIAL_LIGHT } from '@/lib/chartColors'
import { useThemeStore } from '@/store/theme'
import type { DailySalesPoint } from '@/types/api'

interface TooltipPayload {
  active?: boolean
  payload?: { value: number }[]
  label?: string
}

function ChartTooltip({ active, payload, label }: TooltipPayload) {
  if (!active || !payload?.length) return null
  return (
    <div className="rounded-lg border border-app bg-surface-raised px-3 py-2 text-xs shadow-md">
      <div className="font-semibold text-app">{label}</div>
      <div className="text-muted">{formatCurrency(payload[0].value)}</div>
    </div>
  )
}

export function SalesTrendChart({ data }: { data: DailySalesPoint[] }) {
  const isDark = useThemeStore((s) => s.isDark)
  const seq = isDark ? SEQUENTIAL_DARK : SEQUENTIAL_LIGHT

  if (!data.length) {
    return <div className="flex h-64 items-center justify-center text-sm text-muted">No sales recorded yet</div>
  }

  return (
    <ResponsiveContainer width="100%" height={260}>
      <AreaChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="salesFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={seq.stroke} stopOpacity={0.28} />
            <stop offset="100%" stopColor={seq.stroke} stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" vertical={false} className="stroke-app" opacity={0.5} />
        <XAxis
          dataKey="date"
          tick={{ fontSize: 11 }}
          tickLine={false}
          axisLine={false}
          className="fill-muted"
        />
        <YAxis
          tick={{ fontSize: 11 }}
          tickLine={false}
          axisLine={false}
          width={56}
          className="fill-muted"
          tickFormatter={(v: number) => formatCurrency(v, '₹').replace(/\.00$/, '')}
        />
        <Tooltip content={<ChartTooltip />} cursor={{ stroke: seq.stroke, strokeWidth: 1, strokeDasharray: '3 3' }} />
        <Area
          type="monotone"
          dataKey="total"
          stroke={seq.stroke}
          strokeWidth={2}
          fill="url(#salesFill)"
          dot={{ r: 3, fill: seq.stroke, strokeWidth: 0 }}
          activeDot={{ r: 5 }}
        />
      </AreaChart>
    </ResponsiveContainer>
  )
}
