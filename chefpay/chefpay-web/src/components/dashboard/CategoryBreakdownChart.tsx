import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { chartPalette } from '@/lib/chartColors'
import { formatCurrency } from '@/lib/utils'
import { useThemeStore } from '@/store/theme'
import type { CategorySales } from '@/types/api'

const MAX_SLOTS = 5

interface TooltipPayload {
  active?: boolean
  payload?: { payload: CategorySales }[]
}

function ChartTooltip({ active, payload }: TooltipPayload) {
  if (!active || !payload?.length) return null
  const entry = payload[0].payload
  return (
    <div className="rounded-lg border border-app bg-surface-raised px-3 py-2 text-xs shadow-md">
      <div className="font-semibold text-app">{entry.categoryName}</div>
      <div className="text-muted">{formatCurrency(entry.total)}</div>
    </div>
  )
}

/** Categories beyond the fixed 5-slot categorical palette fold into "Other" rather than cycling
 * hues (dataviz skill rule: a 9th series is never a generated color). */
function collapseToSlots(data: CategorySales[]): CategorySales[] {
  if (data.length <= MAX_SLOTS) return data
  const sorted = [...data].sort((a, b) => b.total - a.total)
  const top = sorted.slice(0, MAX_SLOTS - 1)
  const rest = sorted.slice(MAX_SLOTS - 1)
  const otherTotal = rest.reduce((sum, c) => sum + c.total, 0)
  return [...top, { categoryName: 'Other', total: otherTotal }]
}

export function CategoryBreakdownChart({ data }: { data: CategorySales[] }) {
  const isDark = useThemeStore((s) => s.isDark)
  const palette = chartPalette(isDark)
  const slots = collapseToSlots(data)

  if (!slots.length) {
    return <div className="flex h-64 items-center justify-center text-sm text-muted">No sales recorded yet</div>
  }

  return (
    <ResponsiveContainer width="100%" height={260}>
      <BarChart data={slots} layout="vertical" margin={{ top: 8, right: 16, left: 8, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" horizontal={false} className="stroke-app" opacity={0.5} />
        <XAxis
          type="number"
          tick={{ fontSize: 11 }}
          tickLine={false}
          axisLine={false}
          className="fill-muted"
          tickFormatter={(v: number) => formatCurrency(v, '₹').replace(/\.00$/, '')}
        />
        <YAxis
          type="category"
          dataKey="categoryName"
          tick={{ fontSize: 12 }}
          tickLine={false}
          axisLine={false}
          width={96}
          className="fill-muted"
        />
        <Tooltip content={<ChartTooltip />} cursor={{ fill: 'currentColor', opacity: 0.06 }} />
        <Bar dataKey="total" radius={[0, 4, 4, 0]} barSize={18}>
          {slots.map((entry, i) => (
            <Cell key={entry.categoryName} fill={palette[i % palette.length]} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}
