package com.chefpay.server.users;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Device;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.DeviceRepository;
import com.chefpay.core.service.UserAccountService;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AppUserRepository appUserRepository;
    private final DeviceRepository deviceRepository;
    private final UserAccountService userAccountService;
    private final BranchAccessService branchAccessService;

    /** Bistrodesk Phase 2: this used to return every staff account on the whole install with no
     * branch filtering at all - a real gap for a Manager (branch-scoped by design, see {@code
     * DataSeeder}'s ROLE_PERMISSIONS) who should see and manage their own branch's roster, not
     * every other branch's staff too. Unrestricted (see {@code BranchAccessService}) still sees
     * everyone, unchanged. A restricted requester now sees only a user who shares at least one
     * branch with them, PLUS any user with no branch assignment at all (the existing "empty
     * branches = works everywhere" convention - see {@code AppUser#branches}'s javadoc - covers an
     * Owner/Admin-tier account that hasn't been branch-restricted; hiding those from every branch
     * manager felt like the wrong default given they're already visible to literally every existing
     * single-branch install today). This is a judgment call on an otherwise-unspecified requirement -
     * flagged here rather than silently assumed. */
    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW') or hasAuthority('USER_MANAGE')")
    public ApiResponse<List<UserDto>> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        Set<UUID> accessible = branchAccessService.accessibleBranchIds(requester);
        List<UserDto> users = appUserRepository.findAll().stream()
                .filter(u -> accessible == null || u.getBranches().isEmpty()
                        || u.getBranches().stream().anyMatch(b -> accessible.contains(b.getId())))
                .map(this::toDto).toList();
        return ApiResponse.ok(users);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public ApiResponse<UserDto> create(@Valid @RequestBody CreateUserRequest request,
                                        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        AppUser created = userAccountService.createUser(
                request.username(), request.displayName(), request.password(), request.pin(), request.role(),
                request.userCode());
        return ApiResponse.ok(toDto(created));
    }

    /** Item 9's "Auto Generate" suggestions for a new user's code/PIN - purely advisory, nothing
     * persisted. {@code role} is optional (defaults to a generic "USER" prefix on the code
     * suggestion) since the admin may not have picked a role yet when first opening the dialog. */
    @GetMapping("/suggested-credentials")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public ApiResponse<SuggestedCredentialsResponse> suggestedCredentials(@RequestParam(required = false) String role) {
        return ApiResponse.ok(new SuggestedCredentialsResponse(
                userAccountService.generateUserCode(role), userAccountService.generatePin()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public ApiResponse<UserDto> update(@PathVariable UUID id, @RequestBody UpdateUserRequest request,
                                        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        AppUser requester = branchAccessService.resolve(principal);
        AppUser target = appUserRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        assertUserVisible(requester, target);
        AppUser updated = userAccountService.updateUser(id, request.displayName(), request.role(),
                request.active(), request.newPassword(), request.newPin(), request.version());
        return ApiResponse.ok(toDto(updated));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_VIEW') or hasAuthority('USER_MANAGE')")
    public ApiResponse<UserDto> get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        AppUser user = appUserRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        assertUserVisible(requester, user);
        return ApiResponse.ok(toDto(user));
    }

    /** Round 12 §3: assigns which branches this user may work at + their default branch - see
     * {@code UserAccountService#updateUserBranches}'s javadoc. Gated on {@code BRANCH_MANAGE} in
     * addition to {@code USER_MANAGE} - either is sufficient, matching this codebase's usual
     * "several roles can reasonably need this" OR-gate pattern. */
    @PatchMapping("/{id}/branches")
    @PreAuthorize("hasAuthority('USER_MANAGE') or hasAuthority('BRANCH_MANAGE')")
    public ApiResponse<UserDto> updateBranches(@PathVariable UUID id, @RequestBody UpdateUserBranchesRequest request,
                                                @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        AppUser requester = branchAccessService.resolve(principal);
        AppUser target = appUserRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        assertUserVisible(requester, target);
        // Bistrodesk Phase 2: a branch-restricted caller (BRANCH_MANAGE without VIEW_ALL_BRANCHES)
        // must not be able to grant a user access to a branch the caller can't reach themselves -
        // otherwise this endpoint would be a backdoor into touching another branch's roster/data
        // by way of assigning a user there. No-op for an unrestricted caller (see assertAccess).
        if (request.branchIds() != null) {
            for (UUID branchId : request.branchIds()) {
                branchAccessService.assertAccess(requester, branchId);
            }
        }
        if (request.defaultBranchId() != null) {
            branchAccessService.assertAccess(requester, request.defaultBranchId());
        }
        AppUser updated = userAccountService.updateUserBranches(id, request.branchIds(), request.defaultBranchId(), request.version());
        return ApiResponse.ok(toDto(updated));
    }

    /** Phase 2 item 14: assigns which specific terminals this user may log into, one level
     * narrower than {@link #updateBranches} - see {@code AppUser#terminals}'s javadoc for the
     * empty-means-unrestricted convention. */
    @PatchMapping("/{id}/terminals")
    @PreAuthorize("hasAuthority('USER_MANAGE') or hasAuthority('TERMINAL_MANAGE')")
    public ApiResponse<UserDto> updateTerminals(@PathVariable UUID id, @RequestBody UpdateUserTerminalsRequest request,
                                                 @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        AppUser requester = branchAccessService.resolve(principal);
        AppUser target = appUserRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        assertUserVisible(requester, target);
        // Bistrodesk Phase 2: same reasoning as updateBranches above, one level deeper - a
        // restricted caller must not be able to grant a user a login at a terminal that belongs to
        // a branch the caller can't reach themselves. A terminal with no branch assigned yet
        // (Device#branch javadoc) is left unchecked, matching that field's own "unassigned" convention.
        if (request.terminalIds() != null) {
            for (UUID terminalId : request.terminalIds()) {
                Device device = deviceRepository.findById(terminalId)
                        .orElseThrow(() -> ApiException.notFound("Terminal not found"));
                if (device.getBranch() != null) {
                    branchAccessService.assertAccess(requester, device.getBranch().getId());
                }
            }
        }
        AppUser updated = userAccountService.updateUserTerminals(id, request.terminalIds(), request.version());
        return ApiResponse.ok(toDto(updated));
    }

    /** Item 9's "Change PIN" - a distinct action from {@link #update}, so a manager resetting a
     * forgotten PIN never has to touch (or accidentally overwrite) anything else about the account. */
    @PatchMapping("/{id}/pin")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public ApiResponse<UserDto> changePin(@PathVariable UUID id, @Valid @RequestBody ChangePinRequest request,
                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        AppUser requester = branchAccessService.resolve(principal);
        AppUser target = appUserRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        assertUserVisible(requester, target);
        AppUser updated = userAccountService.changePin(id, request.newPin());
        return ApiResponse.ok(toDto(updated));
    }

    /** An admin-driven login-code change, separate from {@link #update} for the same reason {@link
     * #changePin} is - see {@code UserAccountService#changeUserCode}'s javadoc. */
    @PatchMapping("/{id}/user-code")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public ApiResponse<UserDto> changeUserCode(@PathVariable UUID id, @Valid @RequestBody ChangeUserCodeRequest request,
                                                @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        AppUser requester = branchAccessService.resolve(principal);
        AppUser target = appUserRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        assertUserVisible(requester, target);
        AppUser updated = userAccountService.changeUserCode(id, request.newUserCode(), request.version());
        return ApiResponse.ok(toDto(updated));
    }

    /** Bistrodesk Phase 2: applies {@link #list}'s exact same visibility rule to a single-user
     * lookup/mutation by id, so a branch-restricted caller can't reach (or modify) another branch's
     * staff account just by knowing its id, even though {@link #list} already hides it from the
     * roster view. 404 rather than 403 - consistent with {@code BranchAccessService#assertAccess}'s
     * own convention of not confirming a hidden id even exists. */
    private void assertUserVisible(AppUser requester, AppUser target) {
        Set<UUID> accessible = branchAccessService.accessibleBranchIds(requester);
        if (accessible == null || target.getBranches().isEmpty()) {
            return;
        }
        boolean shares = target.getBranches().stream().anyMatch(b -> accessible.contains(b.getId()));
        if (!shares) {
            throw ApiException.notFound("User not found");
        }
    }

    private UserDto toDto(AppUser u) {
        List<Branch> branches = u.getBranches().stream()
                .sorted(Comparator.comparing(Branch::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<Device> terminals = u.getTerminals().stream()
                .sorted(Comparator.comparing(Device::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new UserDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole().getName(),
                u.isActive(), u.getPinHash() != null,
                branches.stream().map(Branch::getId).toList(),
                branches.stream().map(Branch::getName).toList(),
                u.getDefaultBranch() == null ? null : u.getDefaultBranch().getId(),
                u.getCreatedAt(),
                u.getVersion(),
                u.getUserCode(),
                terminals.stream().map(Device::getId).toList(),
                terminals.stream().map(Device::getName).toList());
    }
}
