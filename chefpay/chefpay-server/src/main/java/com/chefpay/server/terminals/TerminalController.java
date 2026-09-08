package com.chefpay.server.terminals;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Device;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.DeviceRepository;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Round 17: "we should have facility to configure more than one terminal for a shop... there
 * should also be the option inside application to see branch and terminal option". Every
 * registered {@code Device} (see its Round 17 javadoc for why this doubles as "Terminal") is
 * listed/managed here - rename a terminal, (re)assign it to a branch, or retire it. Read access is
 * open to any authenticated user (the Branches & Terminals screen is informational for most
 * staff); only {@code RESTAURANT_MANAGE} holders can rename/reassign/retire, same permission the
 * rest of the restaurant/branch configuration endpoints use.
 */
@RestController
@RequestMapping("/api/terminals")
@RequiredArgsConstructor
public class TerminalController {

    private final DeviceRepository deviceRepository;
    private final BranchRepository branchRepository;

    @GetMapping
    public ApiResponse<List<TerminalDto>> list() {
        return ApiResponse.ok(deviceRepository.findAllByOrderByNameAsc().stream().map(this::toDto).toList());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE') or hasAuthority('TERMINAL_MANAGE')")
    public ApiResponse<TerminalDto> update(@PathVariable UUID id, @Valid @RequestBody UpdateTerminalRequest request,
                                            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        Device device = deviceRepository.findById(id).orElseThrow(() -> ApiException.notFound("Terminal not found"));
        if (device.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(Device.class, device.getId());
        }
        if (request.name() != null && !request.name().isBlank()) {
            device.setName(request.name());
        }
        if (request.branchId() != null) {
            Branch branch = branchRepository.findById(request.branchId())
                    .orElseThrow(() -> ApiException.notFound("Branch not found"));
            device.setBranch(branch);
        }
        if (request.active() != null) {
            device.setActive(request.active());
        }
        return ApiResponse.ok(toDto(deviceRepository.save(device)));
    }

    private TerminalDto toDto(Device device) {
        Branch branch = device.getBranch();
        return new TerminalDto(
                device.getId(), device.getName(), device.getTerminalCode(),
                device.getType() == null ? null : device.getType().name(),
                branch == null ? null : branch.getId(),
                branch == null ? null : branch.getName(),
                device.isActive(),
                device.getLastUser() == null ? null : device.getLastUser().getDisplayName(),
                device.getLastSeenAt(),
                device.getVersion(),
                device.getSequenceNo());
    }
}
