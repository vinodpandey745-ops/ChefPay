package com.chefpay.core.repository;

import com.chefpay.core.domain.PrinterProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PrinterProfileRepository extends JpaRepository<PrinterProfile, UUID> {

    List<PrinterProfile> findByBranchIdOrderByNameAsc(UUID branchId);

    List<PrinterProfile> findByBranchIdAndActiveTrueOrderByNameAsc(UUID branchId);
}
