package com.chefpay.server.reservations;

/**
 * Bistrodesk Phase 8 (requirement #24): one {@link com.chefpay.core.domain.Area}'s seating math for
 * a specific requested time window and party size - {@code totalCapacity} is every active table's
 * seating in that area regardless of bookings, {@code freeCapacity} is the subset not already held
 * by an overlapping reservation (see {@code ReservationService}), and {@code sufficient} is just
 * {@code freeCapacity >= the party size that was asked about}, computed once here so neither the
 * controller nor the frontend has to re-derive it.
 */
public record AreaAvailabilityDto(String areaName, int totalCapacity, int freeCapacity, boolean sufficient) {
}
