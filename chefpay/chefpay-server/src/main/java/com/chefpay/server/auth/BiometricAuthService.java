package com.chefpay.server.auth;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.plugin.api.BiometricAuthProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F4.5 - thin wrapper around whatever {@link BiometricAuthProvider} plugins are installed (zero,
 * out of the box - see that interface's javadoc), following the same "empty list = not configured"
 * shape {@code CctvLinkService} already uses for {@code List<CctvProvider>}. The one addition here
 * versus that simpler precedent: a successful biometric read only identifies WHO the person is, so
 * this service still separately checks the matched {@link AppUser} actually holds the required
 * permission - the exact same final check {@code UserAccountService#verifyPinForPermission} already
 * applies to a PIN match, so a biometric step-up can never authorize more than the equivalent PIN
 * entry would have.
 *
 * <p>Also gated on {@link Restaurant#isBiometricOverrideEnabled()} - even if a provider happens to
 * be installed, a restaurant that hasn't explicitly turned this on keeps getting PIN-only behavior,
 * matching every other opt-in feature flag on {@code Restaurant} in this codebase.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BiometricAuthService {

    private final List<BiometricAuthProvider> providers;
    private final AppUserRepository appUserRepository;
    private final RestaurantRepository restaurantRepository;

    /** Never throws - any provider failure is logged and simply treated as "no biometric match",
     * exactly as if no provider were installed at all, so the caller can fall back to requiring a
     * PIN instead. */
    public Optional<AppUser> verifyForPermission(UUID deviceId, String requiredPermissionCode) {
        if (providers.isEmpty() || deviceId == null || requiredPermissionCode == null) {
            return Optional.empty();
        }
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst().orElse(null);
        if (restaurant == null || !restaurant.isBiometricOverrideEnabled()) {
            return Optional.empty();
        }
        for (BiometricAuthProvider provider : providers) {
            try {
                Optional<UUID> matchedUserId = provider.identify(deviceId);
                if (matchedUserId.isEmpty()) {
                    continue;
                }
                Optional<AppUser> match = appUserRepository.findById(matchedUserId.get())
                        .filter(AppUser::isActive)
                        .filter(u -> u.getRole() != null && u.getRole().getPermissions().stream()
                                .anyMatch(p -> requiredPermissionCode.equals(p.getCode())));
                if (match.isPresent()) {
                    return match;
                }
            } catch (Exception ex) {
                log.warn("Biometric provider {} failed to identify on device {} - trying the next provider, if any.",
                        provider.getId(), deviceId, ex);
            }
        }
        return Optional.empty();
    }
}
