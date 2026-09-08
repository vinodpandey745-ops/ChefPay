package com.chefpay.core.domain;

/**
 * Round 15: a genuine reservation-record lifecycle, separate from {@link TableStatus#RESERVED}
 * (which only marks a table as physically blocked-off right now, with no time/party/customer data
 * behind it). A reservation optionally references a table for planning purposes but does NOT
 * automatically flip that table's status - staff still seat a walk-in or reservation into a table
 * explicitly via the existing Tables screen, same as always; this entity is the booking record
 * itself (who, how many, when, notes), not a second source of truth for what a table is doing
 * right now.
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    SEATED,
    CANCELLED,
    NO_SHOW
}
