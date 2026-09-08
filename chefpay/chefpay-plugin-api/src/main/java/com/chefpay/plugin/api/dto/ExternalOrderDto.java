package com.chefpay.plugin.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * An order that originated on an external platform (a food-delivery app, the
 * restaurant's own online-ordering site, etc.) and needs to be imported into
 * ChefPay's kitchen/billing flow.
 */
public record ExternalOrderDto(
        String externalOrderId,
        String sourcePlatformId,
        String customerName,
        String customerPhone,
        String deliveryAddress,
        List<ExternalOrderLineDto> lines,
        String specialInstructions,
        LocalDateTime placedAt,
        FulfillmentType fulfillmentType
) {
    public enum FulfillmentType { DELIVERY, TAKEAWAY, DINE_IN }

    public record ExternalOrderLineDto(String menuItemName, int quantity, String notes) {
    }
}
