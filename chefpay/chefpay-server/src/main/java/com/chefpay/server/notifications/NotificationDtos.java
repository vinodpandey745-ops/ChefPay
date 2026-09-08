package com.chefpay.server.notifications;

import java.time.LocalDateTime;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationDto(UUID id, String category, String message, UUID referenceId, boolean read, LocalDateTime createdAt) {
    }
}
