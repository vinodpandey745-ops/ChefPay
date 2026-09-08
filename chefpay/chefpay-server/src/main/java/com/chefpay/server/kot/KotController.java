package com.chefpay.server.kot;

import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** KOT (Kitchen Order Ticket) listing surface (Round 8). */
@RestController
@RequestMapping("/api/kot")
@RequiredArgsConstructor
public class KotController {

    private final KotTicketService kotTicketService;

    @GetMapping("/tickets")
    @PreAuthorize("hasAuthority('KITCHEN_VIEW') or hasAuthority('KITCHEN_UPDATE') or hasAuthority('MANAGER')")
    public ApiResponse<List<KotDtos.KotTicketDto>> tickets(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(kotTicketService.listRecentTickets(principal == null ? null : principal.userId()));
    }
}
