package com.chefpay.server.purchasing;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Supplier;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bistrodesk branch-isolation release (requirement #6): {@code list} filters to the caller's
 * accessible branches and {@code create} resolves the effective branch mandatorily, same pattern
 * as {@code CustomerController} - see {@link SupplierService}'s javadoc for the full reasoning. */
@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;
    private final BranchRepository branchRepository;
    private final BranchAccessService branchAccessService;

    @GetMapping
    @PreAuthorize("hasAuthority('SUPPLIER_VIEW') or hasAuthority('PURCHASE_ORDER_VIEW') or hasAuthority('PURCHASE_ORDER_CREATE')")
    public ApiResponse<List<SupplierDtos.SupplierDto>> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> accessible = branchAccessService.accessibleBranchIds(principal);
        return ApiResponse.ok(supplierService.listSuppliers(accessible).stream().map(this::toDto).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SUPPLIER_MANAGE')")
    public ApiResponse<SupplierDtos.SupplierDto> create(@Valid @RequestBody SupplierDtos.CreateSupplierRequest request,
                                                         @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        UUID branchId = branchAccessService.resolveEffectiveBranchId(requester, request.branchId());
        Branch branch = branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        return ApiResponse.ok(toDto(supplierService.createSupplier(request.name(), request.contactPerson(), request.phone(),
                request.email(), request.address(), request.notes(), branch, userId(principal))));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPPLIER_MANAGE')")
    public ApiResponse<SupplierDtos.SupplierDto> update(@PathVariable UUID id, @RequestBody SupplierDtos.UpdateSupplierRequest request,
                                                         @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(toDto(supplierService.updateSupplier(id, request.name(), request.contactPerson(), request.phone(),
                request.email(), request.address(), request.notes(), request.active(), request.version(), userId(principal))));
    }

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }

    private SupplierDtos.SupplierDto toDto(Supplier s) {
        return new SupplierDtos.SupplierDto(s.getId(), s.getName(), s.getContactPerson(), s.getPhone(), s.getEmail(),
                s.getAddress(), s.getNotes(), s.isActive(), s.getVersion(),
                s.getBranch() == null ? null : s.getBranch().getId(),
                s.getBranch() == null ? null : s.getBranch().getName());
    }
}
