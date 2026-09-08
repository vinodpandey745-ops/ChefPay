import { useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { RestaurantDto } from '@/types/api'

/** GET /api/restaurant has no permission gate server-side (see RestaurantController), so this is
 * safe to call from any authenticated screen - used by the Settings page (to edit), and by
 * CheckoutModal/PosTerminalPage (read-only) to honor two restaurant-level toggles this round wires
 * up for the first time in chefpay-web: `autoPrintReceiptOnPayment` (receipt preview vs. auto-print)
 * and `kotOptionalEnabled` ("Allow billing without sending to kitchen" - lets a fully-ADDED, never-
 * sent order be billed directly). Both fields already existed server-side/in the JavaFX client;
 * this hook is just the first time chefpay-web reads them. staleTime is generous since restaurant
 * config changes rarely and every POS/Checkout render doesn't need to re-fetch it. */
export function useRestaurantConfig() {
  return useQuery({
    queryKey: ['restaurant'],
    queryFn: () => api.get<RestaurantDto>('/restaurant'),
    staleTime: 60_000,
  })
}
