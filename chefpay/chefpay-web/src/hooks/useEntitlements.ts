import { useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { EntitlementsResponse } from '@/types/api'

/** Phase 2 item 24: the single shared place that answers "is feature X unlocked right now" for
 * this install, backing `GET /api/entitlements` - any authenticated user, computed fresh from the
 * server's clock and the stored subscription/plan every time (see EntitlementService's javadoc -
 * never cached beyond react-query's own staleTime, never trusted from a client-held value). A
 * newly-gated feature is a one-line addition to the plan's feature codes on the platform side, not
 * a new UI conditional here - every caller just checks a code string.
 *
 * <p>Bistrodesk branch-isolation release (requirement #5's confirmed enforcement gap): now also
 * retrofit-gates every pre-existing screen with a real server-side {@code @RequiresFeature} behind
 * it - PurchaseOrdersPage/InventoryPage/ReservationsPage each show a whole-screen {@code
 * FeatureLockedScreen} when their feature is excluded, and MenuEditorPage's single AI Bulk Import
 * control disables itself with a tooltip instead (see each page's own comment for why a
 * whole-screen vs. single-control lock was chosen). The prior note that this hook was "deliberately
 * only used by new/optional feature-gated UI" no longer holds.
 *
 * <p>Follow-up requirement ("Move Appearance Settings to Admin Portal"): AppearancePage (and its
 * CUSTOM_BRANDING lock) has since been removed from this app entirely - see {@code
 * com.chefpay.server.platform.PlatformOwnerController#updateTheme}'s javadoc for where theme
 * editing moved to. */
export function useEntitlements() {
  const query = useQuery({
    queryKey: ['entitlements'],
    queryFn: () => api.get<EntitlementsResponse>('/entitlements'),
    staleTime: 60_000,
  })

  const enabled = new Set(query.data?.enabledFeatures ?? [])

  return {
    ...query,
    enabledFeatures: enabled,
    isEnabled: (featureCode: string) => enabled.has(featureCode),
  }
}
