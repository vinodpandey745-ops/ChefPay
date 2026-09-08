package com.chefpay.core.repository;

import com.chefpay.core.domain.SpecialNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpecialNoteRepository extends JpaRepository<SpecialNote, UUID> {

    List<SpecialNote> findByBranchIdOrderByDisplayOrderAsc(UUID branchId);

    List<SpecialNote> findByBranchIdAndActiveTrueOrderByDisplayOrderAsc(UUID branchId);
}
