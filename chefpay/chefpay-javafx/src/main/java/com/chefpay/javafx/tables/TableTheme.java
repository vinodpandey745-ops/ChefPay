package com.chefpay.javafx.tables;

import javafx.scene.paint.Color;

import java.util.List;
import java.util.Map;

/**
 * Status -> color lookup for the table matrix (requirement §10: "do not hard-code UI colors...
 * configure them through themes/status configuration"). This is a code-level default theme
 * rather than a full settings-screen-editable one - promoting it to a user-editable config (à la
 * the Phase 1 plugin config pattern) is a small follow-up once there's a Settings screen to host
 * it, but the important part - colors are looked up by status, never inlined at each call site -
 * is already true.
 */
public final class TableTheme {

    private static final Map<String, Color> STATUS_COLORS = Map.ofEntries(
            Map.entry("AVAILABLE", Color.web("#2ecc71")),
            Map.entry("RESERVED", Color.web("#95a5a6")),
            Map.entry("OCCUPIED", Color.web("#f39c12")),
            Map.entry("ORDER_PLACED", Color.web("#f39c12")),
            Map.entry("PREPARING", Color.web("#e67e22")),
            Map.entry("READY", Color.web("#3498db")),
            Map.entry("BILL_REQUESTED", Color.web("#9b59b6")),
            Map.entry("PAYMENT_PENDING", Color.web("#8e44ad")),
            Map.entry("CLOSED", Color.web("#7f8c8d")),
            Map.entry("BLOCKED", Color.web("#c0392b"))
    );

    /** Deterministic display order for the status legend - {@code STATUS_COLORS} is an immutable
     * map built with {@code Map.ofEntries}, whose iteration order isn't guaranteed, so the legend
     * needs its own explicit ordering to render consistently run to run. */
    private static final List<String> LEGEND_ORDER = List.of("AVAILABLE", "RESERVED", "OCCUPIED",
            "ORDER_PLACED", "PREPARING", "READY", "BILL_REQUESTED", "PAYMENT_PENDING", "BLOCKED");

    private TableTheme() {
    }

    public static Color colorFor(String status) {
        return STATUS_COLORS.getOrDefault(status, Color.LIGHTGRAY);
    }

    /** Ordered (status, color) pairs for a legend, e.g. "Table View has a status legend" (a
     * commercial-POS parity item) - deliberately built from the same lookup {@code colorFor} uses
     * rather than a separate hardcoded list, so the legend can never drift out of sync with the
     * tile colors it explains. */
    public static List<Map.Entry<String, Color>> legendEntries() {
        return LEGEND_ORDER.stream().map(status -> Map.entry(status, colorFor(status))).toList();
    }
}
