import { useQuery } from '@tanstack/react-query'
import {
  BarChart3,
  Building2,
  Calendar,
  ClipboardList,
  CreditCard,
  Download,
  IndianRupee,
  Layers,
  Printer,
  Receipt,
  Tags,
  TrendingUp,
  UtensilsCrossed,
  Users,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import { BranchSwitcher } from '@/components/common/BranchSwitcher'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/Card'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useEntitlements } from '@/hooks/useEntitlements'
import { api } from '@/lib/api'
import { chartPalette } from '@/lib/chartColors'
import { cn, formatCurrency } from '@/lib/utils'
import { useThemeStore } from '@/store/theme'
import type {
  BranchTotalDto,
  CategoryTotalDto,
  ConsolidatedBranchReportDto,
  EmployeeTotalDto,
  OrderTypeTotalDto,
  PaymentMethodTotalDto,
  ReportTodayDto,
  SalesReportDto,
  TipTotalDto,
  TopItemDto,
} from '@/types/api'

// ---------------------------------------------------------------------------
// Date helpers - all dates are plain 'YYYY-MM-DD' local-calendar strings, the
// exact shape GET /reports/sales?from=&to= expects. Deliberately built from
// local Y/M/D getters rather than `Date#toISOString()` (which is UTC and can
// land on the wrong calendar day for anyone west of Greenwich).
// ---------------------------------------------------------------------------

function toDateInput(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** Inverse of toDateInput - parses a plain 'YYYY-MM-DD' string into a local Date at that
 * calendar day's local midnight. Deliberately NOT `new Date(dateString)`: for a bare date (no
 * time component), that parses as UTC midnight, which lands on the PREVIOUS calendar day for
 * anyone west of Greenwich - the exact class of bug this file's own header comment already
 * warns about for toDateInput's counterpart direction. */
function parseDateInput(dateStr: string): Date {
  const [y, m, d] = dateStr.split('-').map(Number)
  return new Date(y, m - 1, d)
}

function addDays(d: Date, delta: number): Date {
  const copy = new Date(d)
  copy.setDate(copy.getDate() + delta)
  return copy
}

function startOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1)
}

type QuickRangeId = 'today' | 'yesterday' | 'last7' | 'thisMonth' | 'custom'

interface QuickRange {
  id: QuickRangeId
  label: string
  /** Follow-up requirement ("Reports - Date Column": "the correct branch/local time zone is
   * used"): `anchor` is "today" as resolved by the SERVER for the currently-selected branch (see
   * ReportsPage's own `anchorDate`, sourced from `GET /reports/today`) - never the browser's own
   * `new Date()`, which could be a different calendar day than the branch's actual business day
   * (a different timezone, or simply a wrong system clock). */
  range: (anchor: Date) => { from: string; to: string }
}

const QUICK_RANGES: QuickRange[] = [
  { id: 'today', label: 'Today', range: (anchor) => ({ from: toDateInput(anchor), to: toDateInput(anchor) }) },
  {
    id: 'yesterday',
    label: 'Yesterday',
    range: (anchor) => ({ from: toDateInput(addDays(anchor, -1)), to: toDateInput(addDays(anchor, -1)) }),
  },
  {
    id: 'last7',
    label: 'Last 7 Days',
    range: (anchor) => ({ from: toDateInput(addDays(anchor, -6)), to: toDateInput(anchor) }),
  },
  {
    id: 'thisMonth',
    label: 'This Month',
    range: (anchor) => ({ from: toDateInput(startOfMonth(anchor)), to: toDateInput(anchor) }),
  },
]

// ---------------------------------------------------------------------------
// CSV export - purely client-side, built from the already-fetched SalesReportDto.
// There is no backend export endpoint, so this never calls the network.
// ---------------------------------------------------------------------------

function csvCell(value: string | number): string {
  const s = String(value)
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

function csvRow(cells: (string | number)[]): string {
  return cells.map(csvCell).join(',')
}

function buildCsv(report: SalesReportDto): string {
  const lines: string[] = []
  lines.push(csvRow(['Sales Report', `${report.from} to ${report.to}`]))
  lines.push('')
  lines.push(csvRow(['Summary']))
  lines.push(csvRow(['Total Sales', report.totalSales]))
  lines.push(csvRow(['Order Count', report.orderCount]))
  lines.push(csvRow(['Payment Count', report.paymentCount]))
  lines.push(csvRow(['Average Order Value', report.averageOrderValue]))
  lines.push(csvRow(['Total Tips', report.totalTips]))
  lines.push('')

  lines.push(csvRow(['Payment Method Breakdown']))
  lines.push(csvRow(['Method', 'Total', 'Payment Count']))
  report.paymentMethodBreakdown.forEach((p) => lines.push(csvRow([p.method, p.total, p.paymentCount])))
  lines.push('')

  lines.push(csvRow(['Top Items']))
  lines.push(csvRow(['Item', 'Quantity Sold', 'Revenue']))
  report.topItems.forEach((i) => lines.push(csvRow([i.menuItemName, i.quantitySold, i.revenue])))
  lines.push('')

  lines.push(csvRow(['Category Breakdown']))
  lines.push(csvRow(['Category', 'Quantity Sold', 'Revenue']))
  report.categoryBreakdown.forEach((c) => lines.push(csvRow([c.categoryName, c.quantitySold, c.revenue])))
  lines.push('')

  lines.push(csvRow(['Order Type Breakdown']))
  lines.push(csvRow(['Order Type', 'Order Count', 'Revenue']))
  report.orderTypeBreakdown.forEach((o) => lines.push(csvRow([o.orderType, o.orderCount, o.revenue])))
  lines.push('')

  lines.push(csvRow(['Employee Collections']))
  lines.push(csvRow(['Employee', 'Total Collected', 'Payment Count']))
  report.employeeBreakdown.forEach((e) => lines.push(csvRow([e.employeeName, e.totalCollected, e.paymentCount])))
  lines.push('')

  lines.push(csvRow(['Tips by Waiter']))
  lines.push(csvRow(['Waiter', 'Total Tips', 'Order Count']))
  report.tipBreakdown.forEach((t) => lines.push(csvRow([t.waiterName, t.totalTips, t.orderCount])))

  return lines.join('\n')
}

function downloadCsv(report: SalesReportDto) {
  const csv = buildCsv(report)
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `sales-report_${report.from}_to_${report.to}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ---------------------------------------------------------------------------
// Small presentational bits
// ---------------------------------------------------------------------------

function StatCard({
  label,
  value,
  hint,
  icon: Icon,
  tone = 'brand',
}: {
  label: string
  value: string
  hint?: string
  icon: typeof IndianRupee
  tone?: 'brand' | 'success' | 'info' | 'warning'
}) {
  const toneClasses: Record<string, string> = {
    brand: 'bg-brand-50 text-brand-600 dark:bg-brand-900/40 dark:text-brand-300',
    success: 'bg-success-soft text-success',
    info: 'bg-info-soft text-info',
    warning: 'bg-warning-soft text-warning',
  }
  return (
    <Card>
      <CardContent className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted">{label}</div>
          <div className="mt-1.5 truncate text-2xl font-bold text-app">{value}</div>
          {hint && <div className="mt-1 text-xs text-muted">{hint}</div>}
        </div>
        <div className={cn('flex h-10 w-10 shrink-0 items-center justify-center rounded-xl', toneClasses[tone])}>
          <Icon className="h-5 w-5" />
        </div>
      </CardContent>
    </Card>
  )
}

function EmptyState({ label }: { label: string }) {
  return (
    <div className="flex h-56 flex-col items-center justify-center gap-2 text-muted">
      <Receipt className="h-8 w-8" />
      <span className="text-sm font-medium">{label}</span>
    </div>
  )
}

interface Column<T> {
  header: string
  align?: 'left' | 'right'
  cell: (row: T, index: number) => ReactNode
}

function DataTable<T>({ rows, columns, rowKey }: { rows: T[]; columns: Column<T>[]; rowKey: (row: T, i: number) => string }) {
  if (!rows.length) return <EmptyState label="Nothing to show for this range" />
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[480px] border-collapse text-sm">
        <thead>
          <tr className="border-b border-app text-left text-xs font-semibold uppercase tracking-wide text-muted">
            {columns.map((col) => (
              <th key={col.header} className={cn('px-3 py-2', col.align === 'right' && 'text-right')}>
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={rowKey(row, i)} className="border-b border-app/60 last:border-0 hover:bg-app/40">
              {columns.map((col) => (
                <td key={col.header} className={cn('px-3 py-2 text-app', col.align === 'right' && 'text-right')}>
                  {col.cell(row, i)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Charts - same recharts + chartPalette/useThemeStore pattern as the dashboard
// charts (SalesTrendChart / CategoryBreakdownChart / PaymentDistributionChart),
// just re-specialized here for the reports DTOs (different field names).
// ---------------------------------------------------------------------------

function PaymentMethodPie({ data }: { data: PaymentMethodTotalDto[] }) {
  const isDark = useThemeStore((s) => s.isDark)
  const palette = chartPalette(isDark)

  if (!data.length) return <EmptyState label="No payments recorded for this range" />

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
        <Tooltip
          content={({ active, payload }) => {
            if (!active || !payload?.length) return null
            const entry = payload[0].payload as PaymentMethodTotalDto
            return (
              <div className="rounded-lg border border-app bg-surface-raised px-3 py-2 text-xs shadow-md">
                <div className="font-semibold text-app">{entry.method}</div>
                <div className="text-muted">
                  {formatCurrency(entry.total)} · {entry.paymentCount} payment{entry.paymentCount === 1 ? '' : 's'}
                </div>
              </div>
            )
          }}
        />
        <Legend verticalAlign="bottom" height={32} formatter={(value: string) => <span className="text-xs text-muted">{value}</span>} />
      </PieChart>
    </ResponsiveContainer>
  )
}

const MAX_CATEGORY_SLOTS = 5

function collapseCategories(data: CategoryTotalDto[]): CategoryTotalDto[] {
  if (data.length <= MAX_CATEGORY_SLOTS) return data
  const sorted = [...data].sort((a, b) => b.revenue - a.revenue)
  const top = sorted.slice(0, MAX_CATEGORY_SLOTS - 1)
  const rest = sorted.slice(MAX_CATEGORY_SLOTS - 1)
  const other = rest.reduce(
    (acc, c) => ({ categoryName: 'Other', revenue: acc.revenue + c.revenue, quantitySold: acc.quantitySold + c.quantitySold }),
    { categoryName: 'Other', revenue: 0, quantitySold: 0 },
  )
  return [...top, other]
}

function CategoryRevenueChart({ data }: { data: CategoryTotalDto[] }) {
  const isDark = useThemeStore((s) => s.isDark)
  const palette = chartPalette(isDark)
  const slots = collapseCategories(data)

  if (!slots.length) return <EmptyState label="No category sales for this range" />

  return (
    <ResponsiveContainer width="100%" height={Math.max(220, slots.length * 42)}>
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
        <YAxis type="category" dataKey="categoryName" tick={{ fontSize: 12 }} tickLine={false} axisLine={false} width={110} className="fill-muted" />
        <Tooltip
          content={({ active, payload }) => {
            if (!active || !payload?.length) return null
            const entry = payload[0].payload as CategoryTotalDto
            return (
              <div className="rounded-lg border border-app bg-surface-raised px-3 py-2 text-xs shadow-md">
                <div className="font-semibold text-app">{entry.categoryName}</div>
                <div className="text-muted">
                  {formatCurrency(entry.revenue)} · {entry.quantitySold} sold
                </div>
              </div>
            )
          }}
          cursor={{ fill: 'currentColor', opacity: 0.06 }}
        />
        <Bar dataKey="revenue" radius={[0, 4, 4, 0]} barSize={18}>
          {slots.map((entry, i) => (
            <Cell key={entry.categoryName} fill={palette[i % palette.length]} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

function OrderTypeRevenueChart({ data }: { data: OrderTypeTotalDto[] }) {
  const isDark = useThemeStore((s) => s.isDark)
  const palette = chartPalette(isDark)

  if (!data.length) return <EmptyState label="No orders for this range" />

  return (
    <ResponsiveContainer width="100%" height={Math.max(220, data.length * 42)}>
      <BarChart data={data} layout="vertical" margin={{ top: 8, right: 16, left: 8, bottom: 0 }}>
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
          dataKey="orderType"
          tick={{ fontSize: 12 }}
          tickLine={false}
          axisLine={false}
          width={110}
          className="fill-muted"
          tickFormatter={(v: string) => v.replaceAll('_', ' ')}
        />
        <Tooltip
          content={({ active, payload }) => {
            if (!active || !payload?.length) return null
            const entry = payload[0].payload as OrderTypeTotalDto
            return (
              <div className="rounded-lg border border-app bg-surface-raised px-3 py-2 text-xs shadow-md">
                <div className="font-semibold text-app">{entry.orderType.replaceAll('_', ' ')}</div>
                <div className="text-muted">
                  {formatCurrency(entry.revenue)} · {entry.orderCount} order{entry.orderCount === 1 ? '' : 's'}
                </div>
              </div>
            )
          }}
          cursor={{ fill: 'currentColor', opacity: 0.06 }}
        />
        <Bar dataKey="revenue" radius={[0, 4, 4, 0]} barSize={18}>
          {data.map((entry, i) => (
            <Cell key={entry.orderType} fill={palette[i % palette.length]} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

function BranchRevenueChart({ data }: { data: BranchTotalDto[] }) {
  const isDark = useThemeStore((s) => s.isDark)
  const palette = chartPalette(isDark)

  if (!data.length) return <EmptyState label="No branch sales for this range" />

  return (
    <ResponsiveContainer width="100%" height={Math.max(220, data.length * 42)}>
      <BarChart data={data} layout="vertical" margin={{ top: 8, right: 16, left: 8, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" horizontal={false} className="stroke-app" opacity={0.5} />
        <XAxis
          type="number"
          tick={{ fontSize: 11 }}
          tickLine={false}
          axisLine={false}
          className="fill-muted"
          tickFormatter={(v: number) => formatCurrency(v, '₹').replace(/\.00$/, '')}
        />
        <YAxis type="category" dataKey="branchName" tick={{ fontSize: 12 }} tickLine={false} axisLine={false} width={130} className="fill-muted" />
        <Tooltip
          content={({ active, payload }) => {
            if (!active || !payload?.length) return null
            const entry = payload[0].payload as BranchTotalDto
            return (
              <div className="rounded-lg border border-app bg-surface-raised px-3 py-2 text-xs shadow-md">
                <div className="font-semibold text-app">{entry.branchName}</div>
                <div className="text-muted">
                  {formatCurrency(entry.totalSales)} · {entry.orderCount} order{entry.orderCount === 1 ? '' : 's'}
                </div>
              </div>
            )
          }}
          cursor={{ fill: 'currentColor', opacity: 0.06 }}
        />
        <Bar dataKey="totalSales" radius={[0, 4, 4, 0]} barSize={18}>
          {data.map((entry, i) => (
            <Cell key={entry.branchName} fill={palette[i % palette.length]} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

// ---------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------

type TabId = 'overview' | 'items' | 'categories' | 'orderTypes' | 'staff' | 'byBranch'

const TABS: { id: TabId; label: string; icon: typeof BarChart3 }[] = [
  { id: 'overview', label: 'Overview', icon: BarChart3 },
  { id: 'items', label: 'Top Items', icon: UtensilsCrossed },
  { id: 'categories', label: 'Categories', icon: Tags },
  { id: 'orderTypes', label: 'Order Types', icon: Layers },
  { id: 'staff', label: 'Staff & Tips', icon: Users },
]

export function ReportsPage() {
  const todayStr = toDateInput(new Date())
  const [from, setFrom] = useState(todayStr)
  const [to, setTo] = useState(todayStr)
  const [activeQuickRange, setActiveQuickRange] = useState<QuickRangeId>('today')
  const [tab, setTab] = useState<TabId>('overview')
  // Bistrodesk Phase 5: null = no explicit branchId - see BranchSwitcher's own comment for what
  // the server defaults that to (every branch this caller may see).
  const [branchId, setBranchId] = useState<string | null>(null)
  const { isEnabled: isFeatureEnabled } = useEntitlements()
  const canViewByBranch = isFeatureEnabled('ADVANCED_REPORTS')

  // Follow-up requirement ("Reports - Date Column": "the correct branch/local time zone is
  // used"): the server's own branch-aware notion of "today" (GET /reports/today, see that
  // endpoint's javadoc) - used to anchor every quick-range button below instead of the browser's
  // local clock. Falls back to the browser's own date only for the brief moment before this
  // resolves (or if it fails) - the same "best-effort, never blocks the page" degradation this
  // file's hydrate()-style patterns already use elsewhere in this app.
  const todayQuery = useQuery({
    queryKey: ['reports', 'today', branchId],
    queryFn: () => api.get<ReportTodayDto>('/reports/today', { branchId: branchId ?? undefined }),
    staleTime: 60_000,
  })
  const anchorDate = todayQuery.data ? parseDateInput(todayQuery.data.today) : new Date()

  // Once the branch-aware "today" resolves (or the selected branch changes to one in a different
  // timezone), re-anchor the CURRENTLY ACTIVE quick range so an initial paint using the browser's
  // local date - or a stale one from a just-switched branch - never lingers uncorrected. A caller
  // who has picked an explicit custom from/to is never overridden.
  useEffect(() => {
    if (!todayQuery.data || activeQuickRange === 'custom') return
    const qr = QUICK_RANGES.find((r) => r.id === activeQuickRange)
    if (!qr) return
    const { from: f, to: t } = qr.range(parseDateInput(todayQuery.data.today))
    setFrom(f)
    setTo(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [todayQuery.data?.today])

  const reportQuery = useQuery({
    queryKey: ['reports', 'sales', from, to, branchId],
    queryFn: () => api.get<SalesReportDto>('/reports/sales', { from, to, branchId: branchId ?? undefined }),
  })

  /** Round 14 (F2.5) - was built server-side but never wired into this page until Bistrodesk
   * Phase 5. Gated on the same ADVANCED_REPORTS entitlement the endpoint itself requires, checked
   * client-side via useEntitlements() so the tab is simply absent rather than present-then-erroring
   * for a plan that doesn't include it (same pattern useEntitlements's own javadoc documents). */
  const byBranchQuery = useQuery({
    queryKey: ['reports', 'branches', from, to, branchId],
    queryFn: () => api.get<ConsolidatedBranchReportDto>('/reports/branches', { from, to, branchId: branchId ?? undefined }),
    enabled: canViewByBranch,
  })

  const tabs = useMemo(() => (canViewByBranch ? [...TABS, { id: 'byBranch' as const, label: 'By Branch', icon: Building2 }] : TABS), [canViewByBranch])

  function applyQuickRange(qr: QuickRange) {
    const { from: f, to: t } = qr.range(anchorDate)
    setFrom(f)
    setTo(t)
    setActiveQuickRange(qr.id)
  }

  const report = reportQuery.data
  const isEmpty = !!report && report.orderCount === 0

  const branchesSorted = useMemo(() => {
    if (!byBranchQuery.data) return [] as BranchTotalDto[]
    return [...byBranchQuery.data.branches].sort((a, b) => b.totalSales - a.totalSales)
  }, [byBranchQuery.data])

  const topItemsSorted = useMemo(() => {
    if (!report) return [] as TopItemDto[]
    return [...report.topItems].sort((a, b) => b.revenue - a.revenue)
  }, [report])

  const categoriesSorted = useMemo(() => {
    if (!report) return [] as CategoryTotalDto[]
    return [...report.categoryBreakdown].sort((a, b) => b.revenue - a.revenue)
  }, [report])

  const orderTypesSorted = useMemo(() => {
    if (!report) return [] as OrderTypeTotalDto[]
    return [...report.orderTypeBreakdown].sort((a, b) => b.revenue - a.revenue)
  }, [report])

  const employeesSorted = useMemo(() => {
    if (!report) return [] as EmployeeTotalDto[]
    return [...report.employeeBreakdown].sort((a, b) => b.totalCollected - a.totalCollected)
  }, [report])

  const tipsSorted = useMemo(() => {
    if (!report) return [] as TipTotalDto[]
    return [...report.tipBreakdown].sort((a, b) => b.totalTips - a.totalTips)
  }, [report])

  return (
    <div className="space-y-4">
      {/* Print stylesheet: only #reports-print-area is left visible when printing / saving as PDF,
          so the app shell's sidebar/header (rendered by a component we don't own) and the filter
          bar / tab strip (marked no-print) drop out of the printed page. This is the browser's
          native print-to-PDF via window.print() below - not a generated PDF file. */}
      <style>{`
        @media print {
          body * { visibility: hidden; }
          #reports-print-area, #reports-print-area * { visibility: visible; }
          #reports-print-area { position: absolute; inset: 0; width: 100%; padding: 0; }
          .no-print { display: none !important; }
        }
      `}</style>

      <div className="no-print flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold text-app">
            <ClipboardList className="h-5 w-5 text-brand-600" /> Reports
          </h2>
          <p className="mt-0.5 text-sm text-muted">Sales, items, categories, and staff performance for a chosen date range.</p>
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" disabled={!report} onClick={() => report && downloadCsv(report)}>
            <Download className="h-4 w-4" /> Export CSV
          </Button>
          <Button size="sm" variant="secondary" disabled={!report} onClick={() => window.print()}>
            <Printer className="h-4 w-4" /> Print / Save as PDF
          </Button>
        </div>
      </div>

      <Card className="no-print">
        <CardContent className="flex flex-wrap items-center gap-3 py-3">
          <div className="flex items-center gap-1 rounded-xl border border-app bg-app/40 p-1">
            {QUICK_RANGES.map((qr) => (
              <button
                key={qr.id}
                type="button"
                onClick={() => applyQuickRange(qr)}
                className={cn(
                  'rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors',
                  activeQuickRange === qr.id ? 'bg-brand-600 text-white' : 'text-muted hover:bg-app hover:text-app',
                )}
              >
                {qr.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2 text-sm">
            <Calendar className="h-4 w-4 text-muted" />
            <input
              type="date"
              value={from}
              max={to}
              onChange={(e) => {
                setFrom(e.target.value)
                setActiveQuickRange('custom')
              }}
              className="rounded-lg border border-app bg-surface px-2.5 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
            <span className="text-muted">to</span>
            <input
              type="date"
              value={to}
              min={from}
              max={toDateInput(anchorDate)}
              onChange={(e) => {
                setTo(e.target.value)
                setActiveQuickRange('custom')
              }}
              className="rounded-lg border border-app bg-surface px-2.5 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <BranchSwitcher value={branchId} onChange={setBranchId} />
          {reportQuery.isFetching && !reportQuery.isLoading && <span className="text-xs text-muted">Refreshing…</span>}
        </CardContent>
      </Card>

      {reportQuery.isLoading ? (
        <FullPageSpinner label="Loading report…" />
      ) : reportQuery.isError ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center gap-2 py-14 text-center">
            <Receipt className="h-8 w-8 text-danger" />
            <span className="text-sm font-medium text-danger">Could not load the sales report for this range.</span>
            <Button size="sm" variant="secondary" onClick={() => reportQuery.refetch()}>
              Try again
            </Button>
          </CardContent>
        </Card>
      ) : !report ? null : (
        <div id="reports-print-area" className="space-y-4">
          <div className="hidden print:block">
            <h1 className="text-xl font-bold text-app">Sales Report</h1>
            <p className="text-sm text-muted">
              {report.from} to {report.to}
            </p>
          </div>

          {isEmpty ? (
            <Card>
              <CardContent>
                <EmptyState label={`No sales were recorded between ${report.from} and ${report.to}.`} />
              </CardContent>
            </Card>
          ) : (
            <>
              <div className="no-print flex flex-wrap items-center gap-1 rounded-xl border border-app bg-surface p-1">
                {tabs.map((t) => {
                  const Icon = t.icon
                  return (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => setTab(t.id)}
                      className={cn(
                        'flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors',
                        tab === t.id ? 'bg-brand-600 text-white' : 'text-muted hover:bg-app hover:text-app',
                      )}
                    >
                      <Icon className="h-3.5 w-3.5" /> {t.label}
                    </button>
                  )
                })}
              </div>

              {/* Overview */}
              <div className={cn(tab === 'overview' ? 'block' : 'hidden print:block', 'space-y-4')}>
                <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
                  <StatCard label="Total Sales" value={formatCurrency(report.totalSales)} icon={IndianRupee} tone="brand" hint={`${report.paymentCount} payments`} />
                  <StatCard label="Total Orders" value={String(report.orderCount)} icon={ClipboardList} tone="info" />
                  <StatCard label="Avg. Order Value" value={formatCurrency(report.averageOrderValue)} icon={TrendingUp} tone="success" />
                  <StatCard label="Total Tips" value={formatCurrency(report.totalTips)} icon={Users} tone="warning" />
                </div>

                <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                  <Card>
                    <CardHeader>
                      <CardTitle>Payment Methods</CardTitle>
                      <CreditCard className="h-4 w-4 text-muted" />
                    </CardHeader>
                    <CardContent>
                      <PaymentMethodPie data={report.paymentMethodBreakdown} />
                    </CardContent>
                  </Card>
                  <Card>
                    <CardHeader>
                      <CardTitle>Payment Method Detail</CardTitle>
                    </CardHeader>
                    <CardContent>
                      <DataTable
                        rows={report.paymentMethodBreakdown}
                        rowKey={(r) => r.method}
                        columns={[
                          { header: 'Method', cell: (r) => <Badge tone="neutral">{r.method.replaceAll('_', ' ')}</Badge> },
                          { header: 'Payments', align: 'right', cell: (r) => r.paymentCount },
                          { header: 'Total', align: 'right', cell: (r) => <span className="font-semibold">{formatCurrency(r.total)}</span> },
                        ]}
                      />
                    </CardContent>
                  </Card>
                </div>
              </div>

              {/* Top Items */}
              <div className={cn(tab === 'items' ? 'block' : 'hidden print:block')}>
                <Card>
                  <CardHeader>
                    <CardTitle>Top Selling Items</CardTitle>
                    <UtensilsCrossed className="h-4 w-4 text-muted" />
                  </CardHeader>
                  <CardContent>
                    <DataTable
                      rows={topItemsSorted}
                      rowKey={(r, i) => `${r.menuItemName}-${i}`}
                      columns={[
                        { header: '#', cell: (_r, i) => i + 1 },
                        { header: 'Item', cell: (r) => <span className="font-medium">{r.menuItemName}</span> },
                        { header: 'Qty Sold', align: 'right', cell: (r) => r.quantitySold },
                        { header: 'Revenue', align: 'right', cell: (r) => <span className="font-semibold">{formatCurrency(r.revenue)}</span> },
                      ]}
                    />
                  </CardContent>
                </Card>
              </div>

              {/* Categories */}
              <div className={cn(tab === 'categories' ? 'block' : 'hidden print:block', 'space-y-4')}>
                <Card>
                  <CardHeader>
                    <CardTitle>Revenue by Category</CardTitle>
                    <Tags className="h-4 w-4 text-muted" />
                  </CardHeader>
                  <CardContent>
                    <CategoryRevenueChart data={categoriesSorted} />
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader>
                    <CardTitle>Category Detail</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <DataTable
                      rows={categoriesSorted}
                      rowKey={(r) => r.categoryName}
                      columns={[
                        { header: 'Category', cell: (r) => <span className="font-medium">{r.categoryName}</span> },
                        { header: 'Qty Sold', align: 'right', cell: (r) => r.quantitySold },
                        { header: 'Revenue', align: 'right', cell: (r) => <span className="font-semibold">{formatCurrency(r.revenue)}</span> },
                      ]}
                    />
                  </CardContent>
                </Card>
              </div>

              {/* Order Types */}
              <div className={cn(tab === 'orderTypes' ? 'block' : 'hidden print:block', 'space-y-4')}>
                <Card>
                  <CardHeader>
                    <CardTitle>Revenue by Order Type</CardTitle>
                    <Layers className="h-4 w-4 text-muted" />
                  </CardHeader>
                  <CardContent>
                    <OrderTypeRevenueChart data={orderTypesSorted} />
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader>
                    <CardTitle>Order Type Detail</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <DataTable
                      rows={orderTypesSorted}
                      rowKey={(r) => r.orderType}
                      columns={[
                        { header: 'Order Type', cell: (r) => <Badge tone="brand">{r.orderType.replaceAll('_', ' ')}</Badge> },
                        { header: 'Orders', align: 'right', cell: (r) => r.orderCount },
                        { header: 'Revenue', align: 'right', cell: (r) => <span className="font-semibold">{formatCurrency(r.revenue)}</span> },
                      ]}
                    />
                  </CardContent>
                </Card>
              </div>

              {/* Staff & Tips */}
              <div className={cn(tab === 'staff' ? 'block' : 'hidden print:block', 'grid grid-cols-1 gap-4 lg:grid-cols-2')}>
                <Card>
                  <CardHeader>
                    <CardTitle>Employee Collections</CardTitle>
                    <Users className="h-4 w-4 text-muted" />
                  </CardHeader>
                  <CardContent>
                    <DataTable
                      rows={employeesSorted}
                      rowKey={(r) => r.employeeName}
                      columns={[
                        { header: 'Employee', cell: (r) => <span className="font-medium">{r.employeeName}</span> },
                        { header: 'Payments', align: 'right', cell: (r) => r.paymentCount },
                        { header: 'Collected', align: 'right', cell: (r) => <span className="font-semibold">{formatCurrency(r.totalCollected)}</span> },
                      ]}
                    />
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader>
                    <CardTitle>Tips by Waiter</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <DataTable
                      rows={tipsSorted}
                      rowKey={(r) => r.waiterName}
                      columns={[
                        { header: 'Waiter', cell: (r) => <span className="font-medium">{r.waiterName}</span> },
                        { header: 'Orders', align: 'right', cell: (r) => r.orderCount },
                        { header: 'Tips', align: 'right', cell: (r) => <span className="font-semibold">{formatCurrency(r.totalTips)}</span> },
                      ]}
                    />
                  </CardContent>
                </Card>
              </div>

              {/* By Branch - Round 14 (F2.5), wired into the UI in Bistrodesk Phase 5. Only
                  rendered for a caller entitled to ADVANCED_REPORTS (see `tabs` above); the
                  underlying query is itself gated the same way, so this never fires for anyone
                  who wouldn't pass the endpoint's own @RequiresFeature check anyway. */}
              {canViewByBranch && (
                <div className={cn(tab === 'byBranch' ? 'block' : 'hidden print:block', 'space-y-4')}>
                  {byBranchQuery.isLoading ? (
                    <Card>
                      <CardContent className="flex justify-center py-14">
                        <span className="text-sm text-muted">Loading branch breakdown…</span>
                      </CardContent>
                    </Card>
                  ) : byBranchQuery.isError ? (
                    <Card>
                      <CardContent className="flex flex-col items-center justify-center gap-2 py-14 text-center">
                        <Building2 className="h-8 w-8 text-danger" />
                        <span className="text-sm font-medium text-danger">Could not load the branch breakdown for this range.</span>
                        <Button size="sm" variant="secondary" onClick={() => byBranchQuery.refetch()}>
                          Try again
                        </Button>
                      </CardContent>
                    </Card>
                  ) : (
                    <>
                      <Card>
                        <CardHeader>
                          <CardTitle>Revenue by Branch</CardTitle>
                          <Building2 className="h-4 w-4 text-muted" />
                        </CardHeader>
                        <CardContent>
                          <BranchRevenueChart data={branchesSorted} />
                        </CardContent>
                      </Card>
                      <Card>
                        <CardHeader>
                          <CardTitle>Branch Detail</CardTitle>
                        </CardHeader>
                        <CardContent>
                          <DataTable
                            rows={branchesSorted}
                            rowKey={(r) => r.branchId ?? 'unassigned'}
                            columns={[
                              { header: 'Branch', cell: (r) => <span className="font-medium">{r.branchName}</span> },
                              { header: 'Orders', align: 'right', cell: (r) => r.orderCount },
                              { header: 'Revenue', align: 'right', cell: (r) => <span className="font-semibold">{formatCurrency(r.totalSales)}</span> },
                            ]}
                          />
                        </CardContent>
                      </Card>
                    </>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}
