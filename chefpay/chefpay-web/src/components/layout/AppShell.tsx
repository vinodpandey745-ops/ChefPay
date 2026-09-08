import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'

import { useAppearanceStore } from '@/store/appearance'

import { Sidebar } from './Sidebar'
import { Topbar } from './Topbar'

// Kept as a plain lookup rather than react-router's `handle`/`useMatches` - those require a data
// router (createBrowserRouter/RouterProvider), which this app deliberately doesn't use (a plain
// <BrowserRouter> is enough for a route tree with no loaders/actions), so useMatches throws at
// runtime ("must be used within a data router") the moment AppShell renders.
const PAGE_TITLES: Record<string, string> = {
  '/dashboard': 'Dashboard',
  '/pos': 'POS Terminal',
  '/kitchen': 'Kitchen (KDS)',
  '/tables': 'Tables',
  '/orders': 'Orders Log',
  '/appearance': 'Appearance & Branding',
  '/menu': 'Menu Editor',
  '/inventory': 'Inventory',
  '/purchase-orders': 'Purchase Orders',
  '/reports': 'Reports',
  '/customers': 'Customers',
  '/reservations': 'Reservations',
  '/users': 'Users',
  '/settings': 'Settings',
  '/branches-terminals': 'Branches & Terminals',
  '/organization': 'Organization',
  '/subscription': 'Subscription',
}

export function AppShell() {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const location = useLocation()
  const title = PAGE_TITLES[location.pathname] ?? 'Bistrodesk'
  const hydrateAppearance = useAppearanceStore((s) => s.hydrate)

  // Fetch the restaurant-wide appearance config once a session is authenticated (this component
  // only mounts behind RequireAuth) so a theme saved from another terminal is picked up here too.
  useEffect(() => {
    hydrateAppearance()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    // h-dvh (dynamic viewport height) rather than h-screen: on mobile Safari/Chrome, 100vh includes
    // the area the address/toolbar chrome can cover, so h-screen either clips the bottom of the app
    // or jumps size as the chrome collapses on scroll - dvh tracks the actually-visible viewport.
    // Horizontal safe-area padding here (not per-child) covers both the sidebar and the content
    // column in one place for a landscape phone with a side notch/rounded corner.
    <div className="flex h-dvh overflow-hidden bg-app pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
      <Sidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar onMenuClick={() => setSidebarOpen(true)} title={title} />
        <main className="flex-1 overflow-x-hidden overflow-y-auto px-4 pt-4 pb-[calc(1rem_+_env(safe-area-inset-bottom))] sm:px-6 sm:pt-6 sm:pb-[calc(1.5rem_+_env(safe-area-inset-bottom))]">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
