package com.chefpay.server.support;

public final class SupportDtos {

    private SupportDtos() {
    }

    /** Any field null means "not configured yet" - the Help popup hides that field/link rather
     * than rendering a blank one. */
    public record SupportSettingsDto(String supportPhone, String supportEmail,
                                      String termsAndConditions, String privacyPolicy, long version) {
    }

    /** Partial update - a null field leaves that attribute unchanged, same convention as {@code
     * ThemeDtos.UpdateThemeRequest}/{@code PlatformOwnerController#updatePlan}. {@code version} is
     * required and optimistic-locks the update, matching {@code updateTheme}. */
    public record UpdateSupportSettingsRequest(String supportPhone, String supportEmail,
                                                String termsAndConditions, String privacyPolicy, Long version) {
    }
}
