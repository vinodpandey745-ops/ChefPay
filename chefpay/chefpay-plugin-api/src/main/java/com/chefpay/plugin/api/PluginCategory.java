package com.chefpay.plugin.api;

/** Grouping used to organize the Settings &gt; Plugins screen and to route lifecycle events. */
public enum PluginCategory {
    /** Sends the bill / receipt to the customer over some external channel (WhatsApp, SMS, email...). */
    BILL_NOTIFICATION,
    /** Pulls in orders placed on an external platform (food delivery apps, own online-ordering site...). */
    ONLINE_ORDER_SOURCE,
    /** Processes card/UPI/wallet payments through an external payment gateway or POS terminal. */
    PAYMENT_GATEWAY,
    /** Drives a physical cash drawer, typically triggered on cash-payment bill closure. */
    CASH_DRAWER,
    /** Drives a physical or virtual receipt printer. */
    RECEIPT_PRINTER,
    /** Anything else - inventory sync, accounting export, loyalty programs, etc. */
    OTHER,
    /** Round 14 (F4.2): delivers a critical fraud/loss-prevention anomaly alert over WhatsApp, SMS,
     * or push notification - see {@link CriticalAlertChannel}. */
    CRITICAL_ALERT,
    /** Round 14 (F4.4): links an anomaly's timestamp into the restaurant's own CCTV/NVR system -
     * see {@link CctvProvider}. */
    CCTV_INTEGRATION,
    /** F4.5: verifies a manager's identity via a biometric read as an alternative to a PIN for a
     * Level-3 (EOD_OVERRIDE) step-up action - see {@link BiometricAuthProvider}. */
    BIOMETRIC_AUTH
}
