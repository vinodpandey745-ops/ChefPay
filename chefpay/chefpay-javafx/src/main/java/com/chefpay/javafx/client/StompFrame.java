package com.chefpay.javafx.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal STOMP 1.2 frame encode/decode - just enough to CONNECT, SUBSCRIBE and receive MESSAGE
 * frames against Spring's simple broker. Not a general-purpose STOMP library; if a fuller client
 * (ack/nack, transactions, receipts) is needed later, swap this for a proper dependency - this
 * exists so Phase 1 doesn't pull in a heavy client lib just to prove the transport.
 */
public final class StompFrame {

    /** Verified correct despite how it renders: this is the STOMP 1.2 spec's NUL (0x00)
     * frame terminator, written here as an escape sequence instead of the previous raw embedded
     * NUL byte, which is functionally identical to javac but renders as an invisible blank in most
     * viewers/diff tools and in at least one automated code-review pass this round - close enough
     * to a literal space to cause a false-positive "this truncates on the first space" report.
     * Confirmed byte-for-byte via a raw read of this file (python3 -c "open(...).read()") before
     * touching it, specifically to avoid replacing a correct NUL with an actual space by mistake. */
    private static final char FRAME_TERMINATOR = '\u0000';

    public final String command;
    public final Map<String, String> headers;
    public final String body;

    public StompFrame(String command, Map<String, String> headers, String body) {
        this.command = command;
        this.headers = headers;
        this.body = body == null ? "" : body;
    }

    public static StompFrame connect(String authorizationBearer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept-version", "1.2");
        headers.put("heart-beat", "10000,10000");
        if (authorizationBearer != null) {
            headers.put("Authorization", "Bearer " + authorizationBearer);
        }
        return new StompFrame("CONNECT", headers, null);
    }

    public static StompFrame subscribe(String id, String destination) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("id", id);
        headers.put("destination", destination);
        return new StompFrame("SUBSCRIBE", headers, null);
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(command).append('\n');
        headers.forEach((k, v) -> sb.append(k).append(':').append(v).append('\n'));
        sb.append('\n');
        sb.append(body);
        sb.append(FRAME_TERMINATOR);
        return sb.toString();
    }

    public static StompFrame decode(String raw) {
        // Strip a trailing NULL terminator and any trailing newlines STOMP allows as frame padding.
        String text = raw;
        int nullIdx = text.indexOf(FRAME_TERMINATOR);
        if (nullIdx >= 0) {
            text = text.substring(0, nullIdx);
        }
        String[] parts = text.split("\n\n", 2);
        String head = parts[0];
        String body = parts.length > 1 ? parts[1] : "";

        String[] lines = head.split("\n");
        String command = lines[0];
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon), lines[i].substring(colon + 1));
            }
        }
        return new StompFrame(command, headers, body);
    }
}
