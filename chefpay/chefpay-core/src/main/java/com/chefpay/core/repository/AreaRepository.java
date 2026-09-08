package com.chefpay.core.repository;

import com.chefpay.core.domain.Area;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AreaRepository extends JpaRepository<Area, UUID> {

    List<Area> findByBranchIdOrderByDisplayOrderAsc(UUID branchId);

    List<Area> findByBranchIdAndActiveTrueOrderByDisplayOrderAsc(UUID branchId);
}
