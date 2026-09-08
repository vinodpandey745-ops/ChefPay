package com.chefpay.core.domain;

/** Delivery channel for one {@link NotificationLog} row (Round 14, F4.2). {@code EMAIL} is the
 * only channel with a real, working implementation this round ({@code AlertDispatchService} reuses
 * {@code EmailReceiptService}'s SMTP plumbing) - {@code WHATSAPP}/{@code SMS}/{@code PUSH} are
 * logged the same way but always resolve to {@code NotificationLogStatus#SKIPPED_NOT_CONFIGURED}
 * until a real {@code com.chefpay.plugin.api.CriticalAlertChannel} plugin is installed for them
 * (ships with zero built-in implementations - see that interface's javadoc). {@code IN_APP} is the
 * existing {@link Notification} inbox row this same alert also always raises, regardless of which
 * external channels are configured. */
public enum NotificationChannel {
    IN_APP,
    EMAIL,
    WHATSAPP,
    SMS,
    PUSH
}
