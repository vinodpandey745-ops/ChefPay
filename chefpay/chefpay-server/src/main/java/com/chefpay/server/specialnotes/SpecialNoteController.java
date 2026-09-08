package com.chefpay.server.specialnotes;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.SpecialNote;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.SpecialNoteRepository;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.specialnotes.SpecialNoteDtos.CreateSpecialNoteRequest;
import com.chefpay.server.specialnotes.SpecialNoteDtos.SpecialNoteDto;
import com.chefpay.server.specialnotes.SpecialNoteDtos.UpdateSpecialNoteRequest;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Preset quick-pick note catalog CRUD (requirement Round 8) - source for {@code OrderItem#getSpecialInstructions()} quick-picks. */
@RestController
@RequestMapping("/api/special-notes")
@RequiredArgsConstructor
public class SpecialNoteController {

    private final SpecialNoteRepository specialNoteRepository;
    private final BranchRepository branchRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('MENU_VIEW') or hasAuthority('ORDER_CREATE') or hasAuthority('ORDER_MODIFY') or hasAuthority('ORDER_MODIFY_OWN')")
    public ApiResponse<List<SpecialNoteDto>> list(@RequestParam(required = false) UUID branchId) {
        UUID resolvedBranchId = branchId != null ? branchId : resolveBranch(null).getId();
        return ApiResponse.ok(specialNoteRepository.findByBranchIdOrderByDisplayOrderAsc(resolvedBranchId).stream()
                .map(this::toDto).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    public ApiResponse<SpecialNoteDto> create(@Valid @RequestBody CreateSpecialNoteRequest request) {
        Branch branch = resolveBranch(request.branchId());
        SpecialNote note = SpecialNote.builder()
                .branch(branch)
                .text(request.text())
                .displayOrder(request.displayOrder())
                .build();
        SpecialNote saved = specialNoteRepository.save(note);
        return ApiResponse.ok(toDto(saved));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    @Transactional
    public ApiResponse<SpecialNoteDto> update(@PathVariable UUID id, @RequestBody UpdateSpecialNoteRequest request) {
        SpecialNote note = specialNoteRepository.findById(id).orElseThrow(() -> ApiException.notFound("Special note not found"));
        if (note.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(SpecialNote.class, id);
        }

        if (request.text() != null) note.setText(request.text());
        if (request.displayOrder() != null) note.setDisplayOrder(request.displayOrder());
        if (request.active() != null) note.setActive(request.active());

        SpecialNote saved = specialNoteRepository.save(note);
        return ApiResponse.ok(toDto(saved));
    }

    private Branch resolveBranch(UUID branchId) {
        if (branchId != null) {
            return branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        }
        return branchRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("No branch configured"));
    }

    private SpecialNoteDto toDto(SpecialNote n) {
        return new SpecialNoteDto(n.getId(), n.getBranch().getId(), n.getText(), n.getDisplayOrder(), n.isActive(), n.getVersion());
    }
}
