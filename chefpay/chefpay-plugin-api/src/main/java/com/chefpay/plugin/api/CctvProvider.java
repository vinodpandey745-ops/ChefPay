package com.chefpay.plugin.api;

import java.time.LocalDateTime;

/**
 * Extension point for linking an {@code Anomaly}'s detected moment to the restaurant's own
 * CCTV/NVR footage (Round 14, F4.4). Ships with zero built-in implementations, same "document the
 * interface, ship no real vendor integration" pattern {@link GlSyncAdapter} already follows - every
 * commercial NVR/CCTV platform (Hikvision, Dahua, a cloud service like Verkada, etc.) has its own
 * proprietary API/deep-link scheme, and this project has no camera hardware or vendor account to
 * build and test against.
 *
 * <p>Called once, at anomaly-creation time, by {@code CctvLinkService#tryLinkFootage} - never
 * retried later, since a plugin installed after the fact has no way to retroactively produce a
 * link into footage from before it existed anyway. A {@code null} return (or any thrown exception,
 * caught defensively by the caller) simply leaves {@code Anomaly#getCctvFootageUrl()} null, which
 * the review screen already renders as "no footage link available" rather than an error.
 */
public interface CctvProvider extends ChefPayPlugin {

    /**
     * @param cameraId  which camera to seek into - implementation-specific identifier; a
     *                   restaurant with only one camera covering the register may hardcode this
     * @param eventTime the moment to seek the returned link to
     * @return a URL that opens the vendor's viewer pre-seeked to {@code eventTime} on {@code
     *         cameraId}, or {@code null} if no footage is available (already aged out of
     *         retention, camera offline at the time, etc.)
     */
    String buildTimestampLinkedUrl(String cameraId, LocalDateTime eventTime);
}
