package com.chefpay.core.service;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Device;
import com.chefpay.core.domain.Role;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.DeviceRepository;
import com.chefpay.core.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for staff accounts: credential verification and account creation/updates.
 * Deliberately framework-agnostic w.r.t. authentication transport - it hands back a verified
 * {@link AppUser} or empty, and chefpay-server's auth module turns that into a JWT.
 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;
    private final DeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;

    /** Username + password login. */
    @Transactional(readOnly = true)
    public Optional<AppUser> verifyPassword(String username, String rawPassword) {
        return appUserRepository.findByUsernameIgnoreCase(username)
                .filter(AppUser::isActive)
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()));
    }

    /**
     * Fast PIN login for shared terminals. PINs are typically 4-6 digits shared within a small
     * staff roster, so we linear-scan active users and check the hash - fine at restaurant staff
     * scale (tens of users), and avoids ever storing/querying a plaintext PIN.
     */
    @Transactional(readOnly = true)
    public Optional<AppUser> verifyPin(String pin) {
        if (pin == null || pin.isBlank()) {
            return Optional.empty();
        }
        List<AppUser> candidates = appUserRepository.findByActiveTrue();
        return candidates.stream()
                .filter(u -> u.getPinHash() != null)
                .filter(u -> passwordEncoder.matches(pin, u.getPinHash()))
                .findFirst();
    }

    /**
     * Round 13 (AI Backbone Addendum F1.4/NFR-4): Level-3 PIN step-up for a specific sensitive
     * action (a HIGH_VARIANCE cash-count override, a force-finalize with unresolved anomalies, a
     * rule-config edit). Unlike {@link #verifyPin(String)} (login - "any active user whose PIN
     * matches"), this additionally requires the matched user's role to actually hold
     * {@code requiredPermissionCode} - a correct PIN belonging to a cashier does not satisfy an
     * EOD_OVERRIDE check just because it's a valid PIN. Mirrors {@link #verifyPin(String)}'s
     * linear-scan approach (same staff-roster scale reasoning) but stops at the first candidate
     * that is both PIN-correct AND permission-holding, rather than the first PIN match alone.
     */
    @Transactional(readOnly = true)
    public Optional<AppUser> verifyPinForPermission(String pin, String requiredPermissionCode) {
        if (pin == null || pin.isBlank() || requiredPermissionCode == null) {
            return Optional.empty();
        }
        List<AppUser> candidates = appUserRepository.findByActiveTrue();
        return candidates.stream()
                .filter(u -> u.getPinHash() != null)
                .filter(u -> passwordEncoder.matches(pin, u.getPinHash()))
                .filter(u -> u.getRole() != null && u.getRole().getPermissions().stream()
                        .anyMatch(p -> requiredPermissionCode.equals(p.getCode())))
                .findFirst();
    }

    /**
     * Phase 2: the login-identity fix (item 10 of the request). {@code (userCode, pin)} resolves
     * to exactly one candidate account BEFORE the PIN is even checked - a linear PIN-hash scan
     * across every active user (the old {@link #verifyPin(String)} behavior) is never reached for
     * a client that supplies a user code, which every Phase 2 client (both JavaFX and web) always
     * does now. {@link #verifyPin(String)} itself is kept only for {@link
     * #verifyPinForPermission}'s Level-3 step-up use (a different, smaller-scoped duplicate-PIN
     * risk deliberately left as-is for this phase - see PHASE2_ORG_SUBSCRIPTION_DESIGN.md).
     */
    @Transactional(readOnly = true)
    public Optional<AppUser> verifyPinByUserCode(String userCode, String pin) {
        if (userCode == null || userCode.isBlank() || pin == null || pin.isBlank()) {
            return Optional.empty();
        }
        return appUserRepository.findByUserCodeIgnoreCase(userCode.trim())
                .filter(AppUser::isActive)
                .filter(u -> u.getPinHash() != null)
                .filter(u -> passwordEncoder.matches(pin, u.getPinHash()));
    }

    /** Phase 2 item 14: empty {@link AppUser#getTerminals()} means unrestricted (every terminal on
     * every branch this user can already reach) - same convention {@link
     * #isAuthorizedForBranch(AppUser, Branch)} already follows for branches. */
    public boolean isAuthorizedForTerminal(AppUser user, Device terminal) {
        return user.getTerminals().isEmpty() || user.getTerminals().contains(terminal);
    }

    /** Extracted from {@code AuthController}'s Round 12 {@code effectiveBranches} logic so the new
     * Phase 2 login flow can reject an out-of-scope branch selection with a clear message before
     * even looking at the terminal, rather than only ever using this list for display. */
    public boolean isAuthorizedForBranch(AppUser user, Branch branch) {
        return user.getBranches().isEmpty() || user.getBranches().contains(branch);
    }

    @Transactional
    public AppUser createUser(String username, String displayName, String rawPassword, String rawPin, String roleName) {
        return createUser(username, displayName, rawPassword, rawPin, roleName, null);
    }

    /**
     * Phase 2: {@code userCode} is auto-generated from the role name (e.g. "CASH001") when blank -
     * see {@link #generateUserCode(String)} - rather than forcing every caller (including the
     * pre-existing {@link #createUser(String, String, String, String, String)} overload other
     * code still calls) to always supply one.
     */
    @Transactional
    public AppUser createUser(String username, String displayName, String rawPassword, String rawPin,
                               String roleName, String userCode) {
        Role role = roleRepository.findByNameIgnoreCase(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + roleName));

        String resolvedUserCode = (userCode == null || userCode.isBlank())
                ? generateUserCode(roleName)
                : userCode.trim().toUpperCase();
        if (appUserRepository.existsByUserCodeIgnoreCase(resolvedUserCode)) {
            throw new IllegalArgumentException("User code already in use: " + resolvedUserCode);
        }

        AppUser.AppUserBuilder<?, ?> builder = AppUser.builder()
                .username(username)
                .displayName(displayName)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .userCode(resolvedUserCode)
                .active(true);

        if (rawPin != null && !rawPin.isBlank()) {
            builder.pinHash(passwordEncoder.encode(rawPin));
        }

        return appUserRepository.save(builder.build());
    }

    /**
     * Phase 2 item 9: "Auto Generate PIN" / a suggested user code. Role-prefix (first 4 letters,
     * uppercased) + a zero-padded sequence, incrementing past any collision - e.g. the 1st cashier
     * becomes "CASH001", the 2nd "CASH002", and so on regardless of gaps left by deleted/renamed
     * accounts. Purely a suggestion the admin can overwrite before saving (same "auto-generate OR
     * manual entry, admin's choice" posture item 9 asks for for PINs themselves).
     */
    public String generateUserCode(String roleName) {
        String prefix = (roleName == null || roleName.isBlank() ? "USER" : roleName)
                .replaceAll("[^A-Za-z]", "").toUpperCase();
        prefix = prefix.length() >= 4 ? prefix.substring(0, 4) : (prefix + "USER").substring(0, 4);
        for (int i = 1; i <= 9999; i++) {
            String candidate = prefix + String.format("%03d", i);
            if (!appUserRepository.existsByUserCodeIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique user code for role: " + roleName);
    }

    /** Phase 2 item 9: a random 4-digit PIN suggestion, offered alongside manual entry - never
     * silently used without the admin seeing/confirming it first (same posture as {@link
     * #generateUserCode(String)}). */
    public String generatePin() {
        return String.format("%04d", new java.security.SecureRandom().nextInt(10000));
    }

    @Transactional
    public AppUser changePin(UUID userId, String newPin) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setPinHash(passwordEncoder.encode(newPin));
        return appUserRepository.save(user);
    }

    /**
     * Partial update, optimistic-lock checked. Any null parameter (other than expectedVersion)
     * leaves that field unchanged.
     */
    @Transactional
    public AppUser updateUser(UUID userId, String displayName, String roleName, Boolean active,
                               String newPassword, String newPin, long expectedVersion) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (user.getVersion() != expectedVersion) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(AppUser.class, userId);
        }

        // Phase 2 (item 8 / Section A conflict #2 of PHASE2_ORG_SUBSCRIPTION_DESIGN.md): OWNER is
        // "the only role that cannot be deactivated by another user" - checked against the role the
        // account holds BEFORE this update (its role can't be changed away from OWNER and
        // deactivated in the same call either, since that would be the exact same bypass).
        if (active != null && !active && user.getRole() != null && "OWNER".equalsIgnoreCase(user.getRole().getName())) {
            throw new IllegalArgumentException("The Owner account cannot be deactivated.");
        }

        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName);
        }
        if (roleName != null && !roleName.isBlank()) {
            Role role = roleRepository.findByNameIgnoreCase(roleName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + roleName));
            user.setRole(role);
        }
        if (active != null) {
            user.setActive(active);
        }
        if (newPassword != null && !newPassword.isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(newPassword));
        }
        if (newPin != null && !newPin.isBlank()) {
            user.setPinHash(passwordEncoder.encode(newPin));
        }
        return appUserRepository.save(user);
    }

    /**
     * Round 12 §3: assigns which branches this user may work at (an empty {@code branchIds} means
     * "no restriction" - see {@link AppUser#getBranches()}'s javadoc). {@code defaultBranchId}, if
     * given, must be one of {@code branchIds} (or null itself) - otherwise a user's terminal could
     * be told to preselect a branch it isn't even authorized for.
     */
    @Transactional
    public AppUser updateUserBranches(UUID userId, List<UUID> branchIds, UUID defaultBranchId, long expectedVersion) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (user.getVersion() != expectedVersion) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(AppUser.class, userId);
        }

        Set<Branch> branches = new HashSet<>();
        if (branchIds != null) {
            for (UUID branchId : branchIds) {
                branches.add(branchRepository.findById(branchId)
                        .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + branchId)));
            }
        }
        user.setBranches(branches);

        if (defaultBranchId == null) {
            user.setDefaultBranch(null);
        } else {
            Branch defaultBranch = branchRepository.findById(defaultBranchId)
                    .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + defaultBranchId));
            if (!branches.isEmpty() && !branches.contains(defaultBranch)) {
                throw new IllegalArgumentException("Default branch must be one of the assigned branches.");
            }
            user.setDefaultBranch(defaultBranch);
        }
        return appUserRepository.save(user);
    }

    /** Phase 2 item 14: which terminals this user may log into - empty {@code terminalIds} means
     * "no restriction", mirroring {@link #updateUserBranches}'s exact convention one level deeper. */
    @Transactional
    public AppUser updateUserTerminals(UUID userId, List<UUID> terminalIds, long expectedVersion) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (user.getVersion() != expectedVersion) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(AppUser.class, userId);
        }

        Set<Device> terminals = new HashSet<>();
        if (terminalIds != null) {
            for (UUID terminalId : terminalIds) {
                terminals.add(deviceRepository.findById(terminalId)
                        .orElseThrow(() -> new IllegalArgumentException("Terminal not found: " + terminalId)));
            }
        }
        user.setTerminals(terminals);
        return appUserRepository.save(user);
    }

    /** Phase 2: an admin-driven user-code change, separate from {@link #updateUser} (which never
     * touches identity fields, only display/role/active/credentials) - same "a distinct action
     * gets a distinct method" precedent as {@link #changePin(UUID, String)} already follows. */
    @Transactional
    public AppUser changeUserCode(UUID userId, String newUserCode, long expectedVersion) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (user.getVersion() != expectedVersion) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(AppUser.class, userId);
        }
        String resolved = newUserCode.trim().toUpperCase();
        if (!resolved.equalsIgnoreCase(user.getUserCode()) && appUserRepository.existsByUserCodeIgnoreCase(resolved)) {
            throw new IllegalArgumentException("User code already in use: " + resolved);
        }
        user.setUserCode(resolved);
        return appUserRepository.save(user);
    }
}
