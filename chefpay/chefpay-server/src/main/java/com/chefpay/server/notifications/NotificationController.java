package com.chefpay.server.notifications;

import com.chefpay.core.domain.Notification;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Notification inbox surface (low-stock alerts, order-cancellation alerts, etc. - Round 8). */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW') or hasAuthority('MANAGER')")
    public ApiResponse<List<NotificationDtos.NotificationDto>> list(
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly) {
        List<Notification> notifications = unreadOnly ? notificationService.listUnread() : notificationService.listForBranch();
        return ApiResponse.ok(notifications.stream().map(this::toDto).toList());
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW') or hasAuthority('MANAGER')")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(notificationService.countUnread());
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW') or hasAuthority('MANAGER')")
    public ApiResponse<NotificationDtos.NotificationDto> markRead(@PathVariable UUID id) {
        return ApiResponse.ok(toDto(notificationService.markRead(id)));
    }

    private NotificationDtos.NotificationDto toDto(Notification n) {
        return new NotificationDtos.NotificationDto(
                n.getId(), n.getCategory(), n.getMessage(), n.getReferenceId(), n.isRead(), n.getCreatedAt());
    }
}
