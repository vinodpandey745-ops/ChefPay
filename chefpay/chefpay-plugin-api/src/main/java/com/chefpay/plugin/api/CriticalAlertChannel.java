package com.chefpay.plugin.api;

import com.chefpay.plugin.api.dto.NotificationResult;

/**
 * Extension point for delivering a critical fraud/loss-prevention {@code Anomaly} alert (Round 14,
 * F4.2) over WhatsApp, SMS, or push notification. Ships with zero built-in implementations, same
 * "document the interface, ship no real vendor integration" pattern {@link GlSyncAdapter}/{@link
 * OnlineOrderProvider} already follow in this codebase - a real WhatsApp Business API, SMS gateway
 * (Twilio/MSG91/etc.), or push-notification (FCM/APNs) integration each need that vendor's own
 * credentials and API/webhook details to build and test against, none of which this project has.
 *
 * <p>{@code AlertDispatchService} calls every enabled {@code CriticalAlertChannel} whose {@link
 * #getSupportedChannel()} matches a channel the restaurant has configured a recipient for, and
 * records the outcome as a {@code NotificationLog} row regardless of success/failure - see that
 * service's javadoc. Until a real plugin exists for a given channel, every alert on that channel
 * is logged as {@code NotificationLogStatus#SKIPPED_NOT_CONFIGURED}, never silently dropped.
 */
public interface CriticalAlertChannel extends ChefPayPlugin {

    /** Which {@code com.chefpay.core.domain.NotificationChannel} this implementation delivers
     * over - {@code WHATSAPP}, {@code SMS}, or {@code PUSH} (never {@code EMAIL}/{@code IN_APP},
     * which core already handles natively without a plugin). */
    String getSupportedChannel();

    /**
     * @param recipient a phone number, device token, or other channel-specific address
     * @param title     short alert headline, e.g. "[HIGH] Post-Print Void"
     * @param message   full alert body (the anomaly's description)
     * @return a {@link NotificationResult} describing whether the send succeeded - implementations
     *         should return a failure result rather than throwing for an ordinary delivery failure,
     *         so {@code AlertDispatchService} can log it as {@code FAILED} without treating every
     *         non-success as an unexpected error.
     */
    NotificationResult sendAlert(String recipient, String title, String message);
}
