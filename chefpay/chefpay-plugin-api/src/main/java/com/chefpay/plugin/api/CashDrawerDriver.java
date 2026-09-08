package com.chefpay.plugin.api;

/**
 * Extension point for triggering a physical cash drawer, typically fired
 * automatically when a bill is closed with payment method = CASH (configurable
 * in Settings &gt; Cash Drawer). Entirely optional - restaurants without a
 * connected drawer simply leave this unconfigured/disabled.
 */
public interface CashDrawerDriver extends ChefPayPlugin {

    /** Pulse the drawer-open signal. Implementations typically talk to a serial/USB/network kick port. */
    void openDrawer();
}
