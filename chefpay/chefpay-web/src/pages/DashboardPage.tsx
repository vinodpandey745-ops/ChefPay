import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ClipboardList, IndianRupee, PlusCircle, ReceiptText, RefreshCw, Table2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { BranchSwitcher } from '@/components/common/BranchSwitcher'
import { CategoryBreakdownChart } from '@/components/dashboard/CategoryBreakdownChart'
import { PaymentDistributionChart } from '@/components/dashboard/PaymentDistributionChart'
import { RecentActivityList } from '@/components/dashboard/RecentActivityList'
import { SalesTrendChart } from '@/components/dashboard/SalesTrendChart'
import { StatTile } from '@/components/dashboard/StatTile'
import { TopItemsList } from '@/components/dashboard/TopItemsList'
import { Button } from '@/components/ui/Button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/Card'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useLiveTopics } from '@/hooks/useLiveTopics'
import { api } from '@/lib/api'
import { formatCurrency } from '@/lib/utils'
import { useAppearanceStore } from '@/store/appearance'
import { useAuthStore } from '@/store/auth'
import type { DashboardAnalytics, DashboardSummary, OrderDto } from '@/types/api'

function isToday(iso: string): boolean {
  const d = new Date(iso)
  const now = new Date()
  return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
}

export function DashboardPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const displayName = useAuthStore((s) => s.displayName)
  const brandName = useAppearanceStore((s) => s.config.brandName)

  // Bistrodesk branch-isolation follow-up fix: default THIS screen to the terminal's own bound
  // branch, same `terminal?.branchId ?? defaultBranchId` convention already used by
  // TablesPage/PosTerminalPage/ReservationsPage (see those pages' own comments) - not an aggregate
  // "every branch this caller may see" view. Previously this always started at null, so any
  // multi-branch-capable account (VIEW_ALL_BRANCHES, or access to more than one branch) logged in
  // at different physical branch terminals saw the exact same combined restaurant-wide numbers on
  // every one of them instead of that branch's own snapshot - confirmed real-world regression, not
  // a missing setting. Still fully overridable via the BranchSwitcher dropdown below for anyone who
  // deliberately wants the consolidated "All Branches" view (falls back to that same null/aggregate
  // behavior when there's no terminal binding and no default branch to seed from, e.g. a back-office
  // browser session that never went through Terminal Select).
  const { terminal, defaultBranchId } = useAuthStore()
  const [branchId, setBranchId] = useState<string | null>(terminal?.branchId ?? defaultBranchId ?? null)

  const summaryQuery = useQuery({
    queryKey: ['dashboard', 'summary', branchId],
    queryFn: () => api.get<DashboardSummary>('/dashboard/summary', { branchId: branchId ?? undefined }),
    refetchInterval: 30_000,
  })

  const analyticsQuery = useQuery({
    queryKey: ['dashboard', 'analytics', branchId],
    queryFn: () => api.get<DashboardAnalytics>('/dashboard/analytics', { branchId: branchId ?? undefined }),
    refetchInterval: 60_000,
  })

  // Reuses the same /orders/history endpoint the Orders Log is built on, just capped small here -
  // powers both "Recent Activity" and the completed/void breakdown on the Orders tile below.
  // Bistrodesk post-release fix: this never passed branchId at all, so it stayed on
  // OrderController's own default (this caller's whole accessible-branch set - unfiltered for an
  // unrestricted/multi-branch account, regardless of what the Branch Switcher above was set to) even
  // after the summary/analytics tiles were fixed to respect it - "Recent Activity" and the
  // completed/void counts kept showing every branch's orders combined. Now shares the same
  // `branchId` state as summary/analytics, so switching branches here updates every tile together.
  const historyQuery = useQuery({
    queryKey: ['orders', 'history', 'dashboard-recent', branchId],
    queryFn: () => api.get<OrderDto[]>('/orders/history', { limit: 50, branchId: branchId ?? undefined }),
    refetchInterval: 30_000,
  })

  useLiveTopics(['/topic/orders', '/topic/tables'], [
    ['dashboard', 'summary'],
    ['dashboard', 'analytics'],
    ['orders', 'history', 'dashboard-recent'],
  ])

  const todayOrders = useMemo(() => (historyQuery.data ?? []).filter((o) => isToday(o.createdAt)), [historyQuery.data])
  const completedToday = todayOrders.filter((o) => ['SERVED', 'BILLED', 'PAYMENT_PENDING', 'PAID', 'CLOSED'].includes(o.status)).length
  const voidToday = todayOrders.filter((o) => o.status === 'CANCELLED').length
  const recentOrders = (historyQuery.data ?? []).slice(0, 6)

  function refreshAll() {
    queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    queryClient.invalidateQueries({ queryKey: ['orders', 'history', 'dashboard-recent'] })
  }

  if (summaryQuery.isLoading || analyticsQuery.isLoading) {
    return <FullPageSpinner label="Loading dashboard…" />
  }

  const summary = summaryQuery.data
  const analytics = analyticsQuery.data
  const occupancyPct = summary && summary.totalTableCount > 0 ? (summary.occupiedTableCount / summary.totalTableCount) * 100 : 0

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-bold text-app">Welcome back, {displayName ?? 'there'}!</h2>
          <p className="mt-0.5 text-sm text-muted">Here is a snapshot of {brandName} operations today.</p>
        </div>
        <div className="flex items-center gap-2">
          <BranchSwitcher value={branchId} onChange={setBranchId} />
          <Button size="sm" variant="secondary" onClick={refreshAll}>
            <RefreshCw className={summaryQuery.isFetching || analyticsQuery.isFetching ? 'h-4 w-4 animate-spin' : 'h-4 w-4'} /> Refresh
          </Button>
          <Button size="sm" onClick={() => navigate('/pos')}>
            <PlusCircle className="h-4 w-4" /> Start New Bill
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatTile
          label="Today's Sales"
          value={summary ? formatCurrency(summary.todaySalesTotal) : '—'}
          icon={IndianRupee}
          tone="brand"
          hint={summary ? `${summary.todayPaymentCount} payments` : undefined}
        />
        <StatTile
          label="Total Orders"
          value={summary ? String(summary.todayOrderCount) : '—'}
          icon={ClipboardList}
          tone="info"
          hint={`${completedToday} completed, ${voidToday} void`}
        />
        <StatTile
          label="Open Orders"
          value={summary ? String(summary.openOrderCount) : '—'}
          icon={ReceiptText}
          tone="warning"
          hint="Awaiting kitchen / billing"
        />
        <StatTile
          label="Table Occupancy"
          value={summary ? `${summary.occupiedTableCount} / ${summary.totalTableCount}` : '—'}
          icon={Table2}
          tone="success"
          progress={occupancyPct}
        />
      </div>

      {analytics && (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <Card>
            <CardContent className="py-4">
              <div className="text-xs font-semibold uppercase tracking-wide text-muted">Avg. Order Value</div>
              <div className="mt-1 text-lg font-bold text-app">{formatCurrency(analytics.averageOrderValueToday)}</div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="py-4">
              <div className="text-xs font-semibold uppercase tracking-wide text-muted">Discounts Today</div>
              <div className="mt-1 text-lg font-bold text-app">{formatCurrency(analytics.discountTotalToday)}</div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="py-4">
              <div className="text-xs font-semibold uppercase tracking-wide text-muted">Tax Collected</div>
              <div className="mt-1 text-lg font-bold text-app">{formatCurrency(analytics.taxTotalToday)}</div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="py-4">
              <div className="text-xs font-semibold uppercase tracking-wide text-muted">Low Stock Items</div>
              <div className="mt-1 flex items-center gap-1.5 text-lg font-bold text-app">
                {summary?.lowStockItemCount ?? 0}
                {!!summary?.lowStockItemCount && <AlertTriangle className="h-4 w-4 text-danger" />}
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>Sales Trend</CardTitle>
            <ReceiptText className="h-4 w-4 text-muted" />
          </CardHeader>
          <CardContent>
            <SalesTrendChart data={analytics?.salesTrend ?? []} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Payment Methods</CardTitle>
          </CardHeader>
          <CardContent>
            <PaymentDistributionChart data={analytics?.paymentMethods ?? []} />
            {!!analytics?.paymentMethods.length && (
              <div className="mt-2 grid grid-cols-2 gap-2 border-t border-app pt-3">
                {analytics.paymentMethods.map((m) => (
                  <div key={m.method}>
                    <div className="text-[11px] font-semibold uppercase tracking-wide text-muted">{m.method}</div>
                    <div className="text-sm font-bold text-app">{formatCurrency(m.total)}</div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Sales by Category</CardTitle>
          </CardHeader>
          <CardContent>
            <CategoryBreakdownChart data={analytics?.categoryBreakdown ?? []} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Top Selling Items</CardTitle>
          </CardHeader>
          <CardContent>
            <TopItemsList items={analytics?.topItems ?? []} />
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Recent Activity</CardTitle>
          <button type="button" onClick={() => navigate('/orders')} className="text-xs font-semibold text-brand-600 hover:underline">
            View All
          </button>
        </CardHeader>
        <CardContent>
          <RecentActivityList orders={recentOrders} />
        </CardContent>
      </Card>
    </div>
  )
}
