import { lazy, Suspense } from 'react'
import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'

import { AppShell } from '@/components/layout/AppShell'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { LoginPage } from '@/pages/LoginPage'
import { useAuthStore } from '@/store/auth'

// Route-level code splitting: each screen (Dashboard's recharts bundle especially) only downloads
// when a staff member actually opens it, instead of every screen shipping in the initial load a
// POS terminal or KDS tablet pays for on first paint.
const DashboardPage = lazy(() => import('@/pages/DashboardPage').then((m) => ({ default: m.DashboardPage })))
const PosTerminalPage = lazy(() => import('@/pages/PosTerminalPage').then((m) => ({ default: m.PosTerminalPage })))
const KitchenPage = lazy(() => import('@/pages/KitchenPage').then((m) => ({ default: m.KitchenPage })))
const TablesPage = lazy(() => import('@/pages/TablesPage').then((m) => ({ default: m.TablesPage })))
const OrdersLogPage = lazy(() => import('@/pages/OrdersLogPage').then((m) => ({ default: m.OrdersLogPage })))
const MenuEditorPage = lazy(() => import('@/pages/MenuEditorPage').then((m) => ({ default: m.MenuEditorPage })))
const InventoryPage = lazy(() => import('@/pages/InventoryPage').then((m) => ({ default: m.InventoryPage })))
const ReportsPage = lazy(() => import('@/pages/ReportsPage').then((m) => ({ default: m.ReportsPage })))
const CustomersPage = lazy(() => import('@/pages/CustomersPage').then((m) => ({ default: m.CustomersPage })))
const ReservationsPage = lazy(() => import('@/pages/ReservationsPage').then((m) => ({ default: m.ReservationsPage })))
const PurchaseOrdersPage = lazy(() =>
  import('@/pages/PurchaseOrdersPage').then((m) => ({ default: m.PurchaseOrdersPage })),
)
const UsersPage = lazy(() => import('@/pages/UsersPage').then((m) => ({ default: m.UsersPage })))
const SettingsPage = lazy(() => import('@/pages/SettingsPage').then((m) => ({ default: m.SettingsPage })))
const BranchesTerminalsPage = lazy(() =>
  import('@/pages/BranchesTerminalsPage').then((m) => ({ default: m.BranchesTerminalsPage })),
)
const OrganizationPage = lazy(() => import('@/pages/OrganizationPage').then((m) => ({ default: m.OrganizationPage })))
const SubscriptionPage = lazy(() => import('@/pages/SubscriptionPage').then((m) => ({ default: m.SubscriptionPage })))

function Lazy({ children }: { children: ReactNode }) {
  return <Suspense fallback={<FullPageSpinner label="Loading…" />}>{children}</Suspense>
}

function RequireAuth({ children }: { children: ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  if (!isAuthenticated) return <Navigate to="/login" replace />
  return <>{children}</>
}

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      {/* Phase 2: the dedicated Manager/Admin route (Section E) - Username+Password only, the
          Quick PIN tab is never rendered here regardless of role (see LoginPage's `adminOnly`
          prop). Deliberately the SAME component, not a second build, so every existing
          permission-gated page below still lives in one app.

          Bistrodesk Phase 12 rename: this used to live at /admin (browser URL /app/admin, since
          chefpay-web was mounted at base "/app/"). Moving chefpay-web's base to "/" for the URL
          restructuring would have put this at bare /admin - the exact path AdminAppController
          already forwards to the SEPARATE platform-owner Bistrodesk Admin console
          (static/admin/index.html, a different app with a different auth mechanism entirely,
          X-Platform-Owner-Key rather than a per-restaurant JWT). Two @GetMapping methods can't
          both claim the same path - that's a hard Spring MVC "ambiguous mapping" startup failure,
          not just confusing UX - so this branch-level Manager/Admin sign-in route was renamed to
          /staff-login rather than silently colliding with it. */}
      <Route path="/staff-login" element={<LoginPage adminOnly />} />
      <Route path="/staff-login/login" element={<LoginPage adminOnly />} />

      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route
          path="/dashboard"
          element={
            <Lazy>
              <DashboardPage />
            </Lazy>
          }
        />
        <Route
          path="/pos"
          element={
            <Lazy>
              <PosTerminalPage />
            </Lazy>
          }
        />
        <Route
          path="/kitchen"
          element={
            <Lazy>
              <KitchenPage />
            </Lazy>
          }
        />
        <Route
          path="/tables"
          element={
            <Lazy>
              <TablesPage />
            </Lazy>
          }
        />
        <Route
          path="/orders"
          element={
            <Lazy>
              <OrdersLogPage />
            </Lazy>
          }
        />
        {/* Follow-up requirement ("Move Appearance Settings to Admin Portal"): the /appearance
            route is removed - no POS role can edit the global theme any more, only the Bistrodesk
            Admin console can (see PlatformOwnerController#updateTheme). A stale bookmark/QR to
            /appearance now falls through to the catch-all redirect at the bottom of this file. */}
        <Route
          path="/menu"
          element={
            <Lazy>
              <MenuEditorPage />
            </Lazy>
          }
        />
        <Route
          path="/inventory"
          element={
            <Lazy>
              <InventoryPage />
            </Lazy>
          }
        />
        <Route
          path="/reports"
          element={
            <Lazy>
              <ReportsPage />
            </Lazy>
          }
        />
        <Route
          path="/customers"
          element={
            <Lazy>
              <CustomersPage />
            </Lazy>
          }
        />
        <Route
          path="/reservations"
          element={
            <Lazy>
              <ReservationsPage />
            </Lazy>
          }
        />
        <Route
          path="/purchase-orders"
          element={
            <Lazy>
              <PurchaseOrdersPage />
            </Lazy>
          }
        />
        <Route
          path="/users"
          element={
            <Lazy>
              <UsersPage />
            </Lazy>
          }
        />
        <Route
          path="/settings"
          element={
            <Lazy>
              <SettingsPage />
            </Lazy>
          }
        />
        <Route
          path="/branches-terminals"
          element={
            <Lazy>
              <BranchesTerminalsPage />
            </Lazy>
          }
        />
        <Route
          path="/organization"
          element={
            <Lazy>
              <OrganizationPage />
            </Lazy>
          }
        />
        <Route
          path="/subscription"
          element={
            <Lazy>
              <SubscriptionPage />
            </Lazy>
          }
        />
        <Route index element={<Navigate to="/dashboard" replace />} />
      </Route>

      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  )
}

export default App
