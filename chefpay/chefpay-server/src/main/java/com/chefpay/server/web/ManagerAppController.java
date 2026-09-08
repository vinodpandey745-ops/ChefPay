package com.chefpay.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * F4.3 Mobile Manager Companion - explicit forward from {@code /manager} and {@code /manager/} to
 * the actual static entry point. Spring Boot's default static-resource handling serves an exact
 * file path (e.g. {@code /manager/index.html}) reliably, but whether a bare directory-style
 * request also resolves to {@code index.html} depends on the exact Spring Boot version/resource
 * chain configuration - rather than depend on that, this tiny forward makes the friendlier URL
 * (what a manager would actually type or bookmark) work deterministically on every version.
 *
 * <p>Deliberately a plain {@code @Controller} returning a Spring MVC {@code forward:} view, not a
 * redirect - a forward keeps the original URL (including any {@code ?anomaly=<id>} deep-link query
 * string) intact for {@code index.html}'s own JS to read, whereas a redirect could drop it.
 */
@Controller
public class ManagerAppController {

    @GetMapping({"/manager", "/manager/"})
    public String forwardToApp() {
        return "forward:/manager/index.html";
    }
}
