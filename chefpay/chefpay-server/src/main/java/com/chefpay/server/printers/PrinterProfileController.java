package com.chefpay.server.printers;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.PrinterProfile;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.PrinterProfileRepository;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.printers.PrinterProfileDtos.CreatePrinterProfileRequest;
import com.chefpay.server.printers.PrinterProfileDtos.PrinterProfileDto;
import com.chefpay.server.printers.PrinterProfileDtos.UpdatePrinterProfileRequest;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Named printer-configuration CRUD (requirement Round 8). Only persists the plain OS print-queue
 * name string a client sends - this module has no {@code javafx.print} dependency, so the actual
 * printer picker lives client-side (the javafx module's {@code Printer.getAllPrinters()}).
 */
@RestController
@RequestMapping("/api/printer-profiles")
@RequiredArgsConstructor
public class PrinterProfileController {

    private final PrinterProfileRepository printerProfileRepository;
    private final BranchRepository branchRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE')")
    public ApiResponse<List<PrinterProfileDto>> list(@RequestParam(required = false) UUID branchId) {
        UUID resolvedBranchId = branchId != null ? branchId : resolveBranch(null).getId();
        return ApiResponse.ok(printerProfileRepository.findByBranchIdOrderByNameAsc(resolvedBranchId).stream()
                .map(this::toDto).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE')")
    public ApiResponse<PrinterProfileDto> create(@Valid @RequestBody CreatePrinterProfileRequest request) {
        Branch branch = resolveBranch(request.branchId());
        PrinterProfile profile = PrinterProfile.builder()
                .branch(branch)
                .name(request.name())
                .printerName(request.printerName())
                .forBill(request.forBill())
                .forKot(request.forKot())
                .forEbill(request.forEbill())
                .build();
        PrinterProfile saved = printerProfileRepository.save(profile);
        return ApiResponse.ok(toDto(saved));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE')")
    @Transactional
    public ApiResponse<PrinterProfileDto> update(@PathVariable UUID id, @RequestBody UpdatePrinterProfileRequest request) {
        PrinterProfile profile = printerProfileRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Printer profile not found"));
        if (profile.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(PrinterProfile.class, id);
        }

        if (request.name() != null) profile.setName(request.name());
        if (request.printerName() != null) profile.setPrinterName(request.printerName());
        if (request.forBill() != null) profile.setForBill(request.forBill());
        if (request.forKot() != null) profile.setForKot(request.forKot());
        if (request.forEbill() != null) profile.setForEbill(request.forEbill());
        if (request.active() != null) profile.setActive(request.active());

        PrinterProfile saved = printerProfileRepository.save(profile);
        return ApiResponse.ok(toDto(saved));
    }

    private Branch resolveBranch(UUID branchId) {
        if (branchId != null) {
            return branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        }
        return branchRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("No branch configured"));
    }

    private PrinterProfileDto toDto(PrinterProfile p) {
        return new PrinterProfileDto(p.getId(), p.getBranch().getId(), p.getName(), p.getPrinterName(),
                p.isForBill(), p.isForKot(), p.isForEbill(), p.isActive(), p.getVersion());
    }
}
