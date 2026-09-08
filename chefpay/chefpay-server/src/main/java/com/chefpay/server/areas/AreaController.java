package com.chefpay.server.areas;

import com.chefpay.core.domain.Area;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.repository.AreaRepository;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.server.areas.AreaDtos.AreaDto;
import com.chefpay.server.areas.AreaDtos.CreateAreaRequest;
import com.chefpay.server.areas.AreaDtos.UpdateAreaRequest;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Seating-section catalog CRUD (requirement Round 8) - a picker source for {@code RestaurantTable#section}. */
@RestController
@RequestMapping("/api/areas")
@RequiredArgsConstructor
public class AreaController {

    private final AreaRepository areaRepository;
    private final BranchRepository branchRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('TABLE_VIEW') or hasAuthority('TABLE_MANAGE')")
    public ApiResponse<List<AreaDto>> list(@RequestParam(required = false) UUID branchId) {
        UUID resolvedBranchId = branchId != null ? branchId : resolveBranch(null).getId();
        return ApiResponse.ok(areaRepository.findByBranchIdOrderByDisplayOrderAsc(resolvedBranchId).stream()
                .map(this::toDto).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TABLE_MANAGE')")
    public ApiResponse<AreaDto> create(@Valid @RequestBody CreateAreaRequest request) {
        Branch branch = resolveBranch(request.branchId());
        Area area = Area.builder()
                .branch(branch)
                .name(request.name())
                .displayOrder(request.displayOrder())
                .build();
        Area saved = areaRepository.save(area);
        return ApiResponse.ok(toDto(saved));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('TABLE_MANAGE')")
    @Transactional
    public ApiResponse<AreaDto> update(@PathVariable UUID id, @RequestBody UpdateAreaRequest request) {
        Area area = areaRepository.findById(id).orElseThrow(() -> ApiException.notFound("Area not found"));
        if (area.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(Area.class, id);
        }

        if (request.name() != null) area.setName(request.name());
        if (request.displayOrder() != null) area.setDisplayOrder(request.displayOrder());
        if (request.active() != null) area.setActive(request.active());

        Area saved = areaRepository.save(area);
        return ApiResponse.ok(toDto(saved));
    }

    private Branch resolveBranch(UUID branchId) {
        if (branchId != null) {
            return branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        }
        return branchRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("No branch configured"));
    }

    private AreaDto toDto(Area a) {
        return new AreaDto(a.getId(), a.getBranch().getId(), a.getName(), a.getDisplayOrder(), a.isActive(), a.getVersion());
    }
}
