package com.chefpay.plugin.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Extension point for verifying a manager's identity via a biometric read (fingerprint, face,
 * etc.) as an alternative to typing a PIN for a Level-3 ({@code EOD_OVERRIDE}) step-up action - AI
 * Backbone Addendum F4.5. Ships with zero built-in implementations, the same "document the
 * interface, ship no real vendor integration" pattern {@link CctvProvider}/{@link
 * CriticalAlertChannel} already follow in this codebase: every biometric SDK (a USB fingerprint
 * reader's vendor SDK, Windows Hello, a phone's platform biometric API, etc.) is its own
 * proprietary integration, and this project has no biometric hardware or vendor account to build
 * and test against.
 *
 * <p><b>PIN remains mandatory as the fallback method on every terminal - biometric is additive,
 * never a replacement</b> (the spec's own wording). The server-side consumer of this interface
 * only ever calls it when {@code Restaurant#isBiometricOverrideEnabled()} is on AND at least one
 * provider is installed; every existing PIN-based step-up flow (cash-count override, force-finalize)
 * keeps working completely unchanged whether or not a provider is ever installed.
 */
public interface BiometricAuthProvider extends ChefPayPlugin {

    /**
     * Captures one biometric read from {@code deviceId}'s sensor (blocking until the read
     * completes, fails, or times out - implementations should apply their own reasonable timeout)
     * and attempts to match it against enrolled templates.
     *
     * @param deviceId which terminal/device's sensor to read from - a provider whose hardware is
     *                 bound to a specific device may use this to route to the right reader.
     * @return the matched {@code AppUser}'s id if the read matched an enrolled template with
     *         sufficient confidence, or empty for no read/no match/hardware unavailable. The
     *         caller still separately checks the matched user actually holds the required
     *         permission before treating this as a successful step-up - a successful read only
     *         proves WHO it is, not that they're authorized for the action being stepped up.
     */
    Optional<UUID> identify(UUID deviceId);
}
