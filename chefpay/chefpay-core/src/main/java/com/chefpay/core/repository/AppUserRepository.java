package com.chefpay.core.repository;

import com.chefpay.core.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    List<AppUser> findByActiveTrue();

    /** Phase 2: the deterministic-login fix (item 10 of the request) - {@code
     * UserAccountService#verifyPinByUserCode} resolves the exact one candidate account by this,
     * BEFORE checking the PIN at all, rather than scanning every active user's PIN hash. */
    Optional<AppUser> findByUserCodeIgnoreCase(String userCode);

    boolean existsByUserCodeIgnoreCase(String userCode);

    List<AppUser> findByUserCodeIsNull();
}
