package com.chefpay.server.subscription;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bistrodesk Phase 4 (requirement #24's confirmed enforcement gap): marks a controller method - or
 * every mutating/read method on a whole controller, at class level - as requiring one {@link
 * com.chefpay.core.domain.Feature} code to be enabled on the caller's branch subscription. Before
 * this annotation, {@code EntitlementService#isFeatureEnabled} was only ever consulted by {@code
 * SubscriptionController#entitlements} - a purely informational "what does the UI show as
 * locked/unlocked" read - so a client that simply never hid a locked menu item (a bug, or a
 * modified/rogue client bypassing the UI check) could call the real endpoint underneath with zero
 * server-side consequence. {@link RequiresFeatureAspect} is the actual enforcement; this annotation
 * is just the declarative marker every gated controller now carries.
 *
 * <p>Class-level use gates every method on that controller uniformly (the natural shape for a
 * controller that exists entirely to serve one paid feature, e.g. {@code InventoryController});
 * method-level use gates just that one action on an otherwise ungated (or differently-gated)
 * controller (e.g. only {@code ThemeController#update}'s write, not its always-open read). A
 * method-level annotation overrides a class-level one for that method rather than requiring both
 * features - see the aspect's javadoc for the exact resolution order.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresFeature {

    /** The {@link com.chefpay.core.domain.Feature#getCode()} this action requires - matched
     * case-insensitively, same convention {@code FeatureRepository#findByCodeIgnoreCase} already
     * uses everywhere else a feature code is looked up. */
    String value();
}
