package com.chefpay.core.repository;

import com.chefpay.core.domain.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    List<Branch> findAllByOrderByNameAsc();

    /** Phase 2: the client's first-run "enter branch code" screen validates against this - see
     * {@code BranchController#byCode}. */
    Optional<Branch> findByBranchCodeIgnoreCase(String branchCode);

    boolean existsByBranchCodeIgnoreCase(String branchCode);
}
