package com.chefpay.server.logging;

/** Follow-up enhancement ("Enable Logging" - "logging should be both end as per application
 * architecture client side and server side"): the body {@code ClientLogController} accepts from
 * {@code chefpay-web}'s new {@code lib/logger.ts}. {@code level} is one of "error"/"warn"/"info"
 * (anything else is treated as "info" - see {@code ClientLogController#normalizeLevel}); {@code
 * message} is the human-readable summary; {@code context} is optional extra detail (a component
 * name, a stack trace, a caught error's own message) the client wants alongside it; {@code path} is
 * the browser route the error happened on, since a server-side stack trace obviously can't say
 * that. Deliberately no PII/credential fields - see {@code ClientLogController}'s own javadoc for
 * why this endpoint is intentionally minimal. */
public record ClientLogRequest(String level, String message, String context, String path) {
}
