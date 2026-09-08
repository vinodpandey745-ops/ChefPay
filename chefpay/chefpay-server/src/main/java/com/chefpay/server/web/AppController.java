package com.chefpay.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Bistrodesk Web (originally "chefpay-web", UI modernization Phase 1 - React/Tailwind rebuild of
 * the client, backend untouched) - forwards every known client-side route to the SPA's real entry
 * point, the same "forward:, not redirect" reasoning {@link ManagerAppController} documents for
 * /manager.
 *
 * <p>Since chefpay-web is a React Router SPA (unlike /manager, a single static page with no
 * client-side router): a hard refresh or a bookmark on e.g. {@code /kitchen} is a real browser
 * navigation to that path, which Spring's static-resource handler can't resolve to a file on
 * disk. Each such route therefore needs its own explicit forward to {@code /index.html} so React
 * Router can take over client-side from there.
 *
 * <p>Deliberately an explicit route list rather than a {@code /**} wildcard - for two reasons, the
 * second graver than the first: (1) same as always, a wildcard would also swallow requests for the
 * SPA's real static assets ({@code /assets/**}, {@code /manifest.json}, {@code /favicon.svg},
 * {@code /sw.js}, the icon set) and forward those to index.html too, breaking the app outright; (2)
 * now that this app is mounted at the server ROOT rather than under {@code /app/} (Bistrodesk Phase
 * 12), a {@code /**} pattern here would also match every {@code /api/**} request path, and Spring
 * MVC's handler-mapping resolution would then compete with {@code DispatcherServlet}'s REST
 * controllers for those same paths - an explicit, non-overlapping route list is the only safe shape
 * once this controller shares the root namespace with the API instead of living under its own
 * {@code /app} prefix. Add a line here whenever a new top-level route is added to
 * {@code chefpay-web/src/App.tsx}.
 *
 * <p>Bistrodesk Phase 12 rename: {@code /admin}/{@code /admin/login} (chefpay-web's own
 * branch-level Manager/Admin sign-in, {@code LoginPage adminOnly}) moved to
 * {@code /staff-login}/{@code /staff-login/login} - moving this app's base from {@code /app/} to
 * {@code /} would otherwise have put it at the exact same bare {@code /admin} path
 * {@link AdminAppController} already forwards to the SEPARATE platform-owner Bistrodesk Admin
 * console, which is not just confusing but a hard Spring MVC "ambiguous mapping" startup failure
 * (two {@code @GetMapping} methods can't claim the identical path). See {@code App.tsx}'s own route
 * comment for the full reasoning. Old bookmarks/QR codes pointing at the pre-Phase-12
 * {@code /app/*} paths (including the original {@code /app/admin}) are handled by
 * {@link LegacyAppRedirectController}, not this class.
 */
@Controller
public class AppController {

    @GetMapping({
            "/",
            "/login",
            "/staff-login",
            "/staff-login/login",
            "/dashboard",
            "/pos",
            "/kitchen",
            "/tables",
            "/orders",
            "/menu",
            "/inventory",
            "/purchase-orders",
            "/reports",
            "/customers",
            "/reservations",
            "/users",
            "/settings",
            "/branches-terminals",
            "/organization",
            "/subscription",
    })
    public String forwardToApp() {
        return "forward:/index.html";
    }
}
