package com.chefpay.server.logging;

import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.common.CorrelationIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Follow-up enhancement ("Enable Logging" - "logging should be both end as per application
 * architecture client side and server side"). Before this, {@code chefpay-web} had ZERO
 * console.log/error/warn calls anywhere and no way to surface a frontend error anywhere durable -
 * a JS exception on a POS terminal in the field left no trace anyone could investigate after the
 * fact. This is deliberately the SIMPLEST possible bridge: the client posts a short structured
 * message, this controller writes it into the SAME rolling log file every server-side log line
 * already goes to (see {@code application.yml}'s {@code logging.file.name}) under its own logger
 * name so it's trivially greppable/filterable separately from server-side entries. No new table,
 * no new persistence layer, no query/read API - this is a write-only diagnostic channel, not a
 * feature with its own UI.
 *
 * <p>Deliberately {@code permitAll} (see {@code SecurityConfig}) - a login-page failure or a crash
 * before the user has a session is exactly the kind of thing this needs to capture, and it carries
 * no sensitive data by design (see {@link ClientLogRequest}'s javadoc: no credentials, no PII
 * fields exist on the request shape at all). {@code userId} is included when a valid session
 * happens to be present, purely as an extra breadcrumb - never required.
 */
@RestController
@RequestMapping("/api/client-logs")
public class ClientLogController {

    private static final Logger log = LoggerFactory.getLogger("com.chefpay.client");
    private static final int MAX_FIELD_LENGTH = 4000;

    @PostMapping
    public ApiResponse<Void> record(@RequestBody(required = false) ClientLogRequest request,
                                     @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            // Never worth a 400 over - a malformed/empty client log call must never itself become
            // a second problem to troubleshoot. Silently accept and drop.
            return ApiResponse.ok(null);
        }
        String userId = principal == null ? "anonymous" : principal.userId().toString();
        String path = truncate(request.path());
        String context = truncate(request.context());
        String message = truncate(request.message());
        String logLine = "[client] userId=" + userId + " correlationId=" + CorrelationIdHolder.get()
                + (path == null ? "" : " path=" + path)
                + " message=" + message
                + (context == null ? "" : " context=" + context);
        switch (normalizeLevel(request.level())) {
            case "error" -> log.error(logLine);
            case "warn" -> log.warn(logLine);
            default -> log.info(logLine);
        }
        return ApiResponse.ok(null);
    }

    private String normalizeLevel(String level) {
        if (level == null) {
            return "info";
        }
        String lower = level.trim().toLowerCase();
        return switch (lower) {
            case "error", "warn", "info" -> lower;
            default -> "info";
        };
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        String singleLine = value.replace("\n", " \\n ").replace("\r", "");
        return singleLine.length() > MAX_FIELD_LENGTH ? singleLine.substring(0, MAX_FIELD_LENGTH) + "…" : singleLine;
    }
}
