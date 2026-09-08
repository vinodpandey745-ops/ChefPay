package com.chefpay.server.theme;

import com.chefpay.core.domain.ThemeSettings;
import com.chefpay.core.repository.ThemeSettingsRepository;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * chefpay-web's centralized appearance configuration (UI Modernization Phase 1 follow-up -
 * "theme/configuration system... without manually modifying every screen"). One singleton row,
 * same pattern {@link com.chefpay.server.restaurant.RestaurantController} uses for the
 * single-restaurant row: get-or-create on first read so there's nothing to seed.
 *
 * <p>Read requires no special permission - every authenticated terminal (POS, KDS, dashboard)
 * needs to render with the current theme.
 *
 * <p>Follow-up requirement ("Move Appearance Settings to Admin Portal": "Only the BistroDesk
 * team/admin should have permission to modify these settings. Branch-level users should not be
 * able to change the application's global appearance."): the write endpoint that used to live here
 * (gated on {@code RESTAURANT_MANAGE} + {@code CUSTOM_BRANDING} - reachable by any OWNER/ADMIN/
 * Manager POS account) has been REMOVED entirely, same migration this codebase already applied to
 * branch creation and Role/Permission editing - see {@code
 * com.chefpay.server.platform.PlatformOwnerController#updateTheme} for the one remaining path,
 * gated by the platform-owner key rather than any POS role's permission. This controller is now
 * read-only.
 */
@RestController
@RequestMapping("/api/theme")
@RequiredArgsConstructor
public class ThemeController {

    private final ThemeSettingsRepository themeSettingsRepository;

    @GetMapping
    public ApiResponse<ThemeDtos.ThemeSettingsDto> get() {
        ThemeSettings settings = loadOrCreate();
        return ApiResponse.ok(toDto(settings));
    }

    private ThemeSettings loadOrCreate() {
        return themeSettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> themeSettingsRepository.save(ThemeSettings.builder().build()));
    }

    private ThemeDtos.ThemeSettingsDto toDto(ThemeSettings settings) {
        return new ThemeDtos.ThemeSettingsDto(settings.getThemeJson(), settings.getVersion());
    }
}
