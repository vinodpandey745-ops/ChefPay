package com.chefpay.plugin.api;

/**
 * Extension point for pushing a finalized EOD's totals to an external accounting/GL system (e.g.
 * ZohoBooks, Tally) - AI Backbone Addendum F1.7. Ships with zero built-in adapters, same
 * "document the interface, ship no real implementation" pattern {@link OnlineOrderProvider}
 * already follows - a real accounting-system integration needs that vendor's actual API/file-format
 * details and credentials to build and test against, neither of which this project has. When no
 * {@code GlSyncAdapter} is enabled, EOD finalize simply records
 * "NOT_CONFIGURED" and moves on (F1.1's assumption: "where a store has no accounting/GL system
 * connected, the GL sync step is simply skipped, not blocking").
 */
public interface GlSyncAdapter extends ChefPayPlugin {

    /**
     * @param businessDateIso  the EOD session's business date, ISO-8601 ({@code yyyy-MM-dd})
     * @param grossSales       total sales before discount
     * @param netSales         total sales after discount, before tax
     * @param taxAmount        total tax collected
     * @param totalAmount      grand total actually billed
     * @return a short human-readable outcome message; implementations should throw only for a
     *         genuinely unexpected failure - a normal "rejected"/"already synced" outcome should
     *         come back as a message, not an exception, so EOD finalize can record it either way
     *         without treating every non-success as a hard error.
     */
    String syncDay(String businessDateIso, java.math.BigDecimal grossSales, java.math.BigDecimal netSales,
                   java.math.BigDecimal taxAmount, java.math.BigDecimal totalAmount);
}
