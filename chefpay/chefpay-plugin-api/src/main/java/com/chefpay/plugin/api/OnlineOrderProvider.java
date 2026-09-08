package com.chefpay.plugin.api;

import com.chefpay.plugin.api.dto.ExternalOrderDto;
import com.chefpay.plugin.api.dto.MenuItemDto;

import java.util.List;

/**
 * Extension point for accepting orders from an external online-ordering platform
 * (a food-delivery marketplace, the restaurant's own web/app storefront, a
 * third-party aggregator, etc). This is the hook the "Planned Enhancement:
 * Online ordering and takeaway module" from the requirements doc plugs into -
 * v1 ships with a demo/simulated provider only; adding a real one is a matter
 * of implementing this interface and dropping the jar in {@code plugins/}.
 *
 * <p>Core polls {@link #pollNewOrders()} on a schedule (configurable interval,
 * default matches the 1-minute kitchen auto-refresh) and converts any orders
 * returned into normal ChefPay {@code Order} records, tagged with this
 * plugin's id as their source, so they flow through the exact same kitchen
 * and billing lifecycle as a dine-in order.
 */
public interface OnlineOrderProvider extends ChefPayPlugin {

    /** Orders placed on the external platform since the last poll. */
    List<ExternalOrderDto> pollNewOrders();

    /** Notify the external platform that an order has been accepted into the kitchen queue. */
    void acknowledgeOrder(String externalOrderId);

    /** Notify the external platform of a status change (e.g. "preparing", "ready", "out for delivery"). */
    default void pushStatusUpdate(String externalOrderId, String status) {
        // optional - many simple integrations are accept-only
    }

    /** Optionally push the current menu/prices to the external platform so it stays in sync. */
    default void syncMenu(List<MenuItemDto> menu) {
        // optional
    }
}
