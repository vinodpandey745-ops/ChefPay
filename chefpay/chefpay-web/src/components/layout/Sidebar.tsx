import {
  BarChart3,
  Building2,
  CalendarClock,
  ChefHat,
  ClipboardList,
  CreditCard,
  Landmark,
  LayoutDashboard,
  LayoutGrid,
  Package,
  Settings,
  ShoppingCart,
  Truck,
  UserCog,
  Users,
  UtensilsCrossed,
  X,
} from 'lucide-react'
import { NavLink } from 'react-router-dom'

import { cn } from '@/lib/utils'
import { useAppearanceStore } from '@/store/appearance'
import { useAuthStore } from '@/store/auth'

interface NavItem {
  to: string
  label: string
  icon: typeof LayoutDashboard
  /** Omitted = shown to every authenticated user (this app's existing convention - most pages
   * self-gate internally instead of hiding from the nav, e.g. UsersPage's own "Access Restricted"
   * card). Only set this when the backend itself would 403 a user without it AND the design
   * explicitly asks for the nav entry to disappear - Phase 2's Subscription page is the one
   * example ("visible per the role-permission matrix... not hardcoded to Admin-only... simply
   * doesn't appear in the sidebar for roles that lack it"). */
  permission?: string
}

const NAV_ITEMS: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/pos', label: 'POS Terminal', icon: ShoppingCart },
  { to: '/kitchen', label: 'Kitchen (KDS)', icon: ChefHat },
  { to: '/tables', label: 'Tables', icon: LayoutGrid },
  { to: '/orders', label: 'Orders Log', icon: ClipboardList },
  { to: '/menu', label: 'Menu Editor', icon: UtensilsCrossed },
  { to: '/inventory', label: 'Inventory', icon: Package },
  { to: '/purchase-orders', label: 'Purchase Orders', icon: Truck, permission: 'PURCHASE_ORDER_VIEW' },
  { to: '/reports', label: 'Reports', icon: BarChart3 },
  { to: '/customers', label: 'Customers', icon: Users },
  { to: '/reservations', label: 'Reservations', icon: CalendarClock },
  { to: '/users', label: 'Users', icon: UserCog },
  { to: '/branches-terminals', label: 'Branches & Terminals', icon: Building2 },
  { to: '/organization', label: 'Organization', icon: Landmark },
  { to: '/subscription', label: 'Subscription', icon: CreditCard, permission: 'SUBSCRIPTION_VIEW' },
  // Follow-up requirement ("Move Appearance Settings to Admin Portal"): the Appearance screen's
  // nav entry is removed - no POS role can edit the global theme any more, only the Bistrodesk
  // Admin console can (see PlatformOwnerController#updateTheme). The branding itself (brandName/
  // logoDataUrl below) still applies everywhere via useAppearanceStore's read-only hydrate().
  { to: '/settings', label: 'Settings', icon: Settings },
]

interface SidebarProps {
  open: boolean
  onClose: () => void
}

export function Sidebar({ open, onClose }: SidebarProps) {
  const brandName = useAppearanceStore((s) => s.config.brandName)
  const logoDataUrl = useAppearanceStore((s) => s.config.logoDataUrl)
  const { organizationName, terminal, hasPermission } = useAuthStore()
  const branchLine = [organizationName, terminal?.branchName ?? terminal?.name].filter(Boolean).join(' · ')
  const visibleItems = NAV_ITEMS.filter((item) => !item.permission || hasPermission(item.permission))

  return (
    <>
      {open && (
        <div
          className="fixed inset-0 z-30 bg-black/40 lg:hidden"
          onClick={onClose}
          aria-hidden="true"
        />
      )}
      <aside
        data-app-sidebar
        className={cn(
          // max-w-[85vw] keeps the drawer from ever pinning a very narrow phone's viewport edge to
          // edge; the left safe-area padding (cancelled again at lg, where it sits static next to
          // the content column instead of floating over a possibly-notched screen edge) keeps the
          // logo/nav labels clear of a landscape notch or rounded corner.
          'fixed inset-y-0 left-0 z-40 flex w-64 max-w-[85vw] flex-col border-r border-app bg-surface pl-[env(safe-area-inset-left)] transition-transform lg:static lg:translate-x-0 lg:pl-0',
          open ? 'translate-x-0' : '-translate-x-full',
        )}
      >
        <div className="flex min-h-16 shrink-0 items-center justify-between px-5 pt-[env(safe-area-inset-top)]">
          <div className="flex min-w-0 items-center gap-2">
            {logoDataUrl ? (
              <img src={logoDataUrl} alt={brandName} className="h-7 w-7 shrink-0 rounded-lg object-contain" />
            ) : (
              <svg width="28" height="28" viewBox="0 0 32 32" fill="none" aria-hidden="true" className="shrink-0">
                <rect width="32" height="32" rx="9" className="fill-brand-600" />
                <path
                  d="M9 20.5c0-3.6 3.1-6.5 7-6.5s7 2.9 7 6.5"
                  stroke="white"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
                <circle cx="16" cy="10.5" r="2.5" fill="white" />
                <path d="M9 20.5h14" stroke="white" strokeWidth="2" strokeLinecap="round" />
              </svg>
            )}
            <span className="truncate text-base font-bold tracking-tight text-app">{brandName}</span>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="shrink-0 rounded-lg p-2.5 text-muted hover:bg-app lg:hidden"
            aria-label="Close menu"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <nav className="flex-1 space-y-0.5 overflow-y-auto px-3 py-2">
          {visibleItems.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              onClick={onClose}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-brand-50 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200'
                    : 'text-muted hover:bg-app hover:text-app',
                )
              }
            >
              <Icon className="h-4.5 w-4.5 shrink-0" />
              {label}
            </NavLink>
          ))}
        </nav>

        <div className="space-y-0.5 border-t border-app px-5 py-4 pb-[calc(1rem_+_env(safe-area-inset-bottom))] text-xs text-muted">
          {branchLine && <div className="truncate font-medium text-app">{branchLine}</div>}
          <div>Bistrodesk Web · v0.1</div>
        </div>
      </aside>
    </>
  )
}
