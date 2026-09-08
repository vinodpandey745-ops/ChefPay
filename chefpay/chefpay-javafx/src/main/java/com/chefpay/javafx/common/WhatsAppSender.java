package com.chefpay.javafx.common;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Round 11: "give whatsapp ... receipt option." ChefPay has no WhatsApp Business API account or
 * credentials to send messages through automatically (that's a paid third-party integration -
 * Twilio, Meta's own Cloud API, Gupshup, etc. - a restaurant would need to sign up for
 * separately), so this does the next best thing that genuinely works today with zero setup: opens
 * {@code https://wa.me/<phone>?text=<receipt>} in the OS's default browser/WhatsApp handler, which
 * pre-fills the chat with the customer's number and the receipt text ready to send - one click
 * (the customer's own "Send") instead of an automated push. See {@code
 * com.chefpay.server.billing.EmailReceiptService}'s javadoc for the real send-without-a-click
 * email counterpart, and this class's {@link #canSendVia} note for how to swap this out for a real
 * Business API later without touching any calling screen.
 */
public final class WhatsAppSender {

    private WhatsAppSender() {
    }

    /** Opens the OS's WhatsApp handler (desktop app if installed, otherwise WhatsApp Web in the
     * default browser) with {@code phone} and {@code text} pre-filled. {@code phone} should include
     * the country code (digits only, e.g. "919876543210") - wa.me silently fails to resolve a chat
     * for a number that's missing one, so callers should validate/prompt for that rather than
     * guessing a default country code on the customer's behalf.
     *
     * @return true if the OS accepted the request to open the link (NOT proof the customer's
     *         WhatsApp actually opened or that they went on to hit Send - same "accepted, not
     *         confirmed" caveat every other silent/best-effort action in this app documents)
     */
    public static boolean send(String phone, String text) {
        if (phone == null || phone.isBlank() || !java.awt.Desktop.isDesktopSupported()) {
            return false;
        }
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        if (digitsOnly.isBlank()) {
            return false;
        }
        try {
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                return false;
            }
            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
            URI uri = new URI("https://wa.me/" + digitsOnly + "?text=" + encodedText);
            desktop.browse(uri);
            return true;
        } catch (IOException | URISyntaxException | UnsupportedOperationException ex) {
            return false;
        }
    }

    /**
     * Whether {@link #send} has any realistic chance of working on this machine - {@code
     * BillingView} checks this before showing the "WhatsApp Receipt" button/enabling it, rather
     * than letting staff hit a dead action on a headless/kiosk box with no default browser
     * registered. A future swap to a real WhatsApp Business API (Twilio/Meta Cloud API) would
     * replace this whole class's insides but keep {@link #send}'s signature, so {@code
     * BillingView} would need zero changes.
     */
    public static boolean canSendVia() {
        return java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE);
    }
}
