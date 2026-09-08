package com.chefpay.server.branches;

/** Single-terminal add-on to a branch that already has terminals (the "+ Add Terminal" action,
 * distinct from {@link BulkCreateTerminalsRequest}'s "how many do you want?" first-setup prompt).
 * {@code name} is optional - blank falls back to "Terminal 00N" using the assigned sequence number. */
public record CreateTerminalRequest(String name) {
}
