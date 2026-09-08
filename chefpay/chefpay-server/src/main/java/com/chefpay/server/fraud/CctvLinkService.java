package com.chefpay.server.fraud;

import com.chefpay.plugin.api.CctvProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Round 14 (F4.4) - thin wrapper around whatever {@link CctvProvider} plugins are installed
 * (zero, out of the box - see that interface's javadoc). Mirrors {@code EodService#syncToGl}'s
 * exact "empty list = not configured, first enabled implementation wins, any failure is caught and
 * simply leaves the result null" shape for {@code List<GlSyncAdapter>}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CctvLinkService {

    /** Single fixed camera id for a v1, single-camera-at-the-register setup - a real
     * multi-camera deployment would need to pick a camera based on which till/station the
     * anomaly happened at, a refinement left for whoever implements a real {@link CctvProvider}. */
    private static final String DEFAULT_CAMERA_ID = "default";

    private final List<CctvProvider> providers;

    /** Never throws - any provider failure is logged and simply leaves the anomaly's footage link
     * null, exactly as if no provider were installed at all. */
    public String tryLinkFootage(LocalDateTime eventTime) {
        if (providers.isEmpty()) {
            return null;
        }
        try {
            return providers.get(0).buildTimestampLinkedUrl(DEFAULT_CAMERA_ID, eventTime);
        } catch (Exception ex) {
            log.error("CCTV footage link failed for event at {} - continuing without it.", eventTime, ex);
            return null;
        }
    }
}
