package com.chefpay.server.purchasing.whatsapp;

/**
 * The three WhatsApp Business API providers this integration supports (branch-isolation release,
 * requirement #4: "write a whatsapp meta, twilio, 360dialog integration and make it configurable
 * inside setting"). Deliberately just an enum + a lenient parser, not a persisted entity - mirrors
 * {@code com.chefpay.server.ai.AiProvider} exactly, including WHY: {@code Branch.whatsappProvider}
 * stores the enum name as a plain string (see V43__bistrodesk_branch_whatsapp_config.sql), since
 * there's exactly one active provider per branch at a time, no need for a join table.
 */
public enum WhatsAppProvider {
    META,
    TWILIO,
    DIALOG360;

    /** Never throws - an unrecognized/blank code (not yet configured, or a stale value) just means
     * "no provider selected", handled by {@link WhatsAppService#isConfigured} the same as a missing
     * key/sender number. */
    public static WhatsAppProvider fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return WhatsAppProvider.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
