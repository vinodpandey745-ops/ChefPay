package com.chefpay.server.notifications;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Notification;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.NotificationRepository;
import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * System-generated Notification inbox (low-stock alerts, order-cancellation alerts, etc. -
 * Round 8). {@link #create(String, String, UUID)} deliberately takes no branch parameter: this
 * codebase is effectively single-branch today (see {@code BranchRepository}'s/{@code DataSeeder}'s
 * javadoc - exactly one {@code Branch} is ever seeded), so "the" branch is resolved here the same
 * way the rest of the app already behaves, rather than plumbing a branch id through every call
 * site that wants to raise an alert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final BranchRepository branchRepository;

    /**
     * Creates and saves one notification. A notification is a side-effect of some other operation
     * (stock deduction, order cancellation) and must never break that operation - any failure here
     * (including "no branch exists yet") is caught and swallowed rather than propagated, so a
     * broken notification write can never roll back or fail the caller's real transaction.
     */
    public Notification create(String category, String message, UUID referenceId) {
        try {
            Branch branch = branchRepository.findAll().stream().findFirst().orElse(null);
            if (branch == null) {
                return null;
            }
            Notification notification = Notification.builder()
                    .branch(branch)
                    .category(category)
                    .message(message)
                    .referenceId(referenceId)
                    .build();
            return notificationRepository.save(notification);
        } catch (Exception ex) {
            log.error("Failed to create notification (category={}, referenceId={}): {}", category, referenceId, ex.getMessage(), ex);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> listForBranch() {
        Branch branch = branchRepository.findAll().stream().findFirst().orElse(null);
        if (branch == null) {
            return List.of();
        }
        return notificationRepository.findByBranchIdOrderByCreatedAtDesc(branch.getId());
    }

    @Transactional(readOnly = true)
    public List<Notification> listUnread() {
        Branch branch = branchRepository.findAll().stream().findFirst().orElse(null);
        if (branch == null) {
            return List.of();
        }
        return notificationRepository.findByBranchIdAndReadFalseOrderByCreatedAtDesc(branch.getId());
    }

    @Transactional(readOnly = true)
    public long countUnread() {
        Branch branch = branchRepository.findAll().stream().findFirst().orElse(null);
        if (branch == null) {
            return 0L;
        }
        return notificationRepository.countByBranchIdAndReadFalse(branch.getId());
    }

    @Transactional
    public Notification markRead(UUID id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Notification not found"));
        notification.setRead(true);
        return notificationRepository.save(notification);
    }
}
