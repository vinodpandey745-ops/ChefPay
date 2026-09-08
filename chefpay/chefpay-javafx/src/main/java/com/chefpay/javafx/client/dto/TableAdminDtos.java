package com.chefpay.javafx.client.dto;

import java.util.UUID;

/** Mirrors {@code com.chefpay.server.tables.CreateTableRequest}/{@code UpdateTableRequest} -
 * used by the new Table Setup screen. Kept separate from {@link TableDto} (the read model, used
 * everywhere else - the table matrix, billing, etc.) so that screen isn't the only place needing
 * to know about these two write-only shapes. */
public final class TableAdminDtos {

    private TableAdminDtos() {
    }

    public record CreateTableRequest(UUID floorId, String name, int seatingCapacity, String section,
                                      Integer gridRow, Integer gridColumn) {
    }

    /** Any null field (except version) leaves that attribute unchanged. */
    public record UpdateTableRequest(String name, Integer seatingCapacity, String section, String status,
                                      Integer gridRow, Integer gridColumn, Boolean active, long version) {
    }
}
