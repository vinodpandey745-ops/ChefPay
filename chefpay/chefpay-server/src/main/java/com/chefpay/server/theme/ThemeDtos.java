package com.chefpay.server.theme;

public final class ThemeDtos {

    private ThemeDtos() {
    }

    /** {@code themeJson}: null means "no customization saved yet." */
    public record ThemeSettingsDto(String themeJson, long version) {
    }

    public record UpdateThemeRequest(String themeJson, long version) {
    }
}
