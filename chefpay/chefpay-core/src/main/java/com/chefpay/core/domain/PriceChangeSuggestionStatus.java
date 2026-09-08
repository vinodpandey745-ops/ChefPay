package com.chefpay.core.domain;

/** Lifecycle of one {@link PriceChangeSuggestion} (F3.2). Matches the SRS's exact vocabulary
 * (Section 8/F3.2 technical notes: "status: PENDING/APPLIED/DISMISSED") - {@code APPLIED} means a
 * Level-2/3 user explicitly approved it AND {@code PriceSuggestionService#apply} wrote the new
 * price onto {@link MenuItem#getPrice()} in the same transaction, logged with the reason (F3.2:
 * "the system must never change a live menu price automatically" - automatically without review,
 * not "never on explicit human approval"; F3.2 elsewhere: "every applied change is logged... for
 * audit"). {@code DISMISSED} means a manager reviewed it and chose not to change the price at all. */
public enum PriceChangeSuggestionStatus {
    PENDING,
    APPLIED,
    DISMISSED
}
