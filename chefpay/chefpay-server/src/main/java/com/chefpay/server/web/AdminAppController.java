package com.chefpay.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Bistrodesk Phase 4 (requirement #24's Admin UI) - explicit forward from {@code /admin} and
 * {@code /admin/} to the actual static entry point, mirroring {@link ManagerAppController}'s exact
 * reasoning: Spring Boot's default static-resource handling serves an exact file path (e.g.
 * {@code /admin/index.html}) reliably, but a bare directory-style request resolving to
 * {@code index.html} isn't guaranteed across every Spring Boot version/resource-chain
 * configuration - this tiny forward makes the friendlier URL work deterministically.
 *
 * <p>This is the platform-owner's own console (plan CRUD, feature CRUD, branch/subscription
 * assignment via {@code PlatformOwnerController}'s API) - a different audience and a different
 * authentication mechanism ({@code X-Platform-Owner-Key}, not a per-restaurant JWT) than every
 * other app mounted in this server, which is exactly why it lives at its own top-level path rather
 * than inside {@code chefpay-web}'s React app.
 */
@Controller
public class AdminAppController {

    @GetMapping({"/admin", "/admin/"})
    public String forwardToApp() {
        return "forward:/admin/index.html";
    }
}
