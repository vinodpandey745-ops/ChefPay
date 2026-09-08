package com.chefpay.core.repository;

import com.chefpay.core.domain.Tax;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaxRepository extends JpaRepository<Tax, UUID> {

    List<Tax> findByActiveTrueOrderByNameAsc();

    List<Tax> findAllByOrderByNameAsc();

    /** Global (branch-less) lookup - kept for compatibility, but {@code BillingService#resolveTax}
     * now uses the branch-aware pair below instead, since Bistrodesk Phase 3 allows the same code
     * to appear once globally and once per overriding branch. */
    Optional<Tax> findByCode(String code);

    Optional<Tax> findByCodeIgnoreCaseAndIdNot(String code, UUID id);

    Optional<Tax> findByActiveTrueAndDefaultRateTrue();

    // ---- Bistrodesk Phase 3: branch-scoped resolution (requirement #6) ----

    /** The branch-specific override for this code, if this install has configured one. */
    Optional<Tax> findByCodeAndBranchId(String code, UUID branchId);

    /** The GLOBAL rate for this code (no branch override configured). */
    Optional<Tax> findByCodeAndBranchIsNull(String code);

    boolean existsByCodeIgnoreCaseAndBranchId(String code, UUID branchId);

    boolean existsByCodeIgnoreCaseAndBranchIsNull(String code);

    /** The active branch-specific default-rate tax for this branch, if one is configured. */
    Optional<Tax> findByActiveTrueAndDefaultRateTrueAndBranchId(UUID branchId);

    /** The active GLOBAL default-rate tax (no branch override configured). */
    Optional<Tax> findByActiveTrueAndDefaultRateTrueAndBranchIsNull();
}
