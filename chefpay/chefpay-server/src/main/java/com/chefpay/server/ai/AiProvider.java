package com.chefpay.server.ai;

/**
 * The three providers Round 10's "bring-your-own-key" AI integration supports. Deliberately just
 * an enum + a lenient parser, not a persisted entity - {@code Restaurant.aiProvider} stores the
 * enum name as a plain string (see V13__round10_ai_config.sql) since there's exactly one active
 * provider per restaurant at a time, no need for a join table.
 */
public enum AiProvider {
    OPENAI,
    ANTHROPIC,
    GEMINI;

    /** Never throws - an unrecognized/blank code (not yet configured, or a stale value) just means
     * "no provider selected", handled by {@code AiService.assertEnabled} the same as a missing key. */
    public static AiProvider fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return AiProvider.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
