package com.chefpay.server.tables;

import java.util.UUID;

/** Bistrodesk follow-up requirement #5 (table creation/seeding fix): the minimal shape {@code
 * TableController#listFloors} returns - just enough for an "Add Table" floor picker (today, always
 * exactly one "Ground Floor" per branch via {@link TableSeedingService}, but multiple floors per
 * branch are a real, supported shape - {@code Floor#displayOrder} already exists for ordering more
 * than one). */
public record TableFloorDto(UUID id, String name) {
}
