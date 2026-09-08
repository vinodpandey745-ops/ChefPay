package com.chefpay.core.repository;

import com.chefpay.core.domain.AuditChainState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AuditChainStateRepository extends JpaRepository<AuditChainState, String> {

    /** Same pessimistic-write-lock pattern as {@code NumberSequenceRepository#findForUpdate} - see
     * that interface's javadoc for why REQUIRED (not REQUIRES_NEW) propagation on the caller side
     * matters here too. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuditChainState s where s.id = :id")
    Optional<AuditChainState> findForUpdate(@Param("id") String id);
}
