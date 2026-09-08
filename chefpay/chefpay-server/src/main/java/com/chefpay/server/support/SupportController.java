package com.chefpay.server.support;

import com.chefpay.core.domain.SupportSettings;
import com.chefpay.core.repository.SupportSettingsRepository;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user-side Help popup's configurable content - support phone/email and Terms &amp;
 * Conditions/Privacy Policy text, saved from the BistroDesk Admin Panel (see
 * {@code PlatformOwnerController#getSupport}/{@code #updateSupport}). One singleton row, same
 * pattern {@link com.chefpay.server.theme.ThemeController} uses for the appearance settings row:
 * get-or-create on first read so there's nothing to seed.
 *
 * <p>Read requires no special permission - every authenticated terminal's Help popup needs to
 * render the currently configured support info, same posture as {@code ThemeController#get}. This
 * controller is read-only; the write endpoint lives on {@code PlatformOwnerController}, gated by
 * the platform-owner key so only the BistroDesk team/admin can change it - no POS role, however
 * senior, may edit this.
 */
@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    private final SupportSettingsRepository supportSettingsRepository;

    @GetMapping
    public ApiResponse<SupportDtos.SupportSettingsDto> get() {
        SupportSettings settings = loadOrCreate();
        return ApiResponse.ok(toDto(settings));
    }

    private SupportSettings loadOrCreate() {
        return supportSettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> supportSettingsRepository.save(SupportSettings.builder().build()));
    }

    private SupportDtos.SupportSettingsDto toDto(SupportSettings settings) {
        return new SupportDtos.SupportSettingsDto(settings.getSupportPhone(), settings.getSupportEmail(),
                settings.getTermsAndConditions(), settings.getPrivacyPolicy(), settings.getVersion());
    }
}
