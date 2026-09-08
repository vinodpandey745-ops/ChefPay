package com.chefpay.server.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Bistrodesk Phase 12 (URL restructuring, requirement #26): before this phase, chefpay-web was
 * mounted at {@code /app/} (see {@link AppController}'s pre-Phase-12 javadoc history); it now
 * serves from the server root instead. A real deployment may already have {@code /app/*} URLs
 * printed on receipts, saved as browser bookmarks, or embedded in table QR codes, so those old
 * paths must keep working rather than 404ing the moment this server upgrades - this controller
 * issues a 302 redirect from every {@code /app/*} request to its new root-level equivalent (e.g.
 * {@code /app/kitchen} - {@code /kitchen}, {@code /app/admin} - {@code /staff-login}, the one path
 * that also changed name in the same phase - see {@code App.tsx}'s route comment).
 *
 * <p>A redirect, not a forward like {@link AppController} uses for its own (already-current) route
 * list: the point here is specifically to update the browser's address bar / whatever bookmarked
 * the old URL, not just to serve the right content at the old address transparently. 302 (temporary),
 * not 301 (permanent), deliberately - a permanent redirect risks a browser caching the mapping
 * indefinitely, which would be the wrong call while this is a brand-new migration rather than a
 * settled, permanent one-to-one rename.
 *
 * <p>A single {@code /app/**} pattern (rather than {@link AppController}'s per-route explicit
 * list) is safe here specifically because every match is unconditionally redirected to a
 * corresponding root-level path with no static asset in between - there is nothing left under
 * {@code /app/**} any more for a wildcard to accidentally swallow (the old {@code static/app/}
 * build output was removed as part of this same migration), unlike {@link AppController}'s or
 * {@code SecurityConfig}'s reasons for staying explicit.
 *
 * <p><b>{@code /app/admin} is the one path that is NOT a plain prefix strip.</b> A naive
 * "/app" - "" rewrite would send it to bare {@code /admin} - which, post-Phase-12, is
 * {@link AdminAppController}'s platform-owner console, an entirely different app with a different
 * audience and auth mechanism than what {@code /app/admin} used to mean (chefpay-web's own
 * branch-level Manager/Admin sign-in). That old path is special-cased to its true new home,
 * {@code /staff-login}, instead - see {@code App.tsx}'s route-rename comment for the full story.
 */
@Controller
public class LegacyAppRedirectController {

    @GetMapping({"/app", "/app/**"})
    public RedirectView redirectLegacyAppPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String rest = path.length() <= 4 ? "" : path.substring(4); // strip the leading "/app"

        String newPath;
        if (rest.equals("/admin") || rest.equals("/admin/")) {
            newPath = "/staff-login";
        } else if (rest.equals("/admin/login")) {
            newPath = "/staff-login/login";
        } else if (rest.isEmpty()) {
            newPath = "/";
        } else {
            newPath = rest;
        }

        String query = request.getQueryString();
        RedirectView view = new RedirectView(query != null ? newPath + "?" + query : newPath);
        view.setStatusCode(org.springframework.http.HttpStatus.FOUND);
        return view;
    }
}
