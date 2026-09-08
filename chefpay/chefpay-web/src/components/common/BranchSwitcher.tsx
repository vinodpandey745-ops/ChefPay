import { useQuery } from '@tanstack/react-query'
import { Building2 } from 'lucide-react'

import { api } from '@/lib/api'
import { useAuthStore } from '@/store/auth'
import type { AccessibleBranchDto } from '@/types/api'

/**
 * Bistrodesk Phase 5: the branch drill-down control for Dashboard/Reports, backed by GET
 * /api/branches/accessible (already pre-filtered to exactly what this caller may see - see that
 * endpoint's own javadoc). `value: null` means "no explicit branchId" - the server's own default
 * for that (BranchAccessService#resolveBranchFilter) is "every branch this caller may see," which
 * is already correct for both an unrestricted caller (sees the whole restaurant) and a restricted
 * one (sees their own assigned branch(es) combined) - so this component never needs to know which
 * kind of caller it's showing the picker to beyond the VIEW_ALL_BRANCHES check below, which only
 * changes the label on that first option.
 *
 * Renders nothing when there is no real choice to make (0 or 1 accessible branch) - the exact same
 * "don't show a switcher with nothing to switch between" principle the Terminal Select/Branch Code
 * screens already follow elsewhere in this app.
 */
export function BranchSwitcher({ value, onChange, className }: {
  value: string | null
  onChange: (branchId: string | null) => void
  className?: string
}) {
  const hasPermission = useAuthStore((s) => s.hasPermission)
  const canViewAll = hasPermission('VIEW_ALL_BRANCHES')

  const branchesQuery = useQuery({
    queryKey: ['branches', 'accessible'],
    queryFn: () => api.get<AccessibleBranchDto[]>('/branches/accessible'),
    staleTime: 5 * 60_000,
  })

  const branches = branchesQuery.data ?? []
  if (branches.length <= 1) {
    return null
  }

  return (
    <div className={`flex items-center gap-1.5 ${className ?? ''}`}>
      <Building2 className="h-4 w-4 text-muted" />
      <select
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value || null)}
        className="rounded-lg border border-app bg-surface px-2.5 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
      >
        <option value="">{canViewAll ? 'All Branches' : 'All My Branches'}</option>
        {branches.map((b) => (
          <option key={b.id} value={b.id}>
            {b.name}
          </option>
        ))}
      </select>
    </div>
  )
}
