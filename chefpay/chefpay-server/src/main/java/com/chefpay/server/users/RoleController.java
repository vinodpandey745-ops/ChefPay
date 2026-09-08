package com.chefpay.server.users;

import com.chefpay.core.domain.Permission;
import com.chefpay.core.domain.Role;
import com.chefpay.core.repository.RoleRepository;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Bistrodesk follow-up requirement #7 ("move the role and permission to admin portal which can
 * be modified only by bistrodesk team or admin. remove completely from application"): this used to
 * also expose {@code PATCH /{id}/permissions}, reachable by any POS account holding {@code
 * ROLE_MANAGE} (OWNER/ADMIN by default - see {@code DataSeeder#PERMISSION_CODES}), which is exactly
 * the capability this requirement asks to take away from the POS app entirely. That write endpoint
 * (and the sibling {@code GET /api/permissions} catalog it needed) is now gone - the equivalent
 * lives at {@code PlatformOwnerController#updateRolePermissions}, reachable only with the
 * install's {@code X-Platform-Owner-Key}, never by any {@code AppUser} session however senior.
 *
 * <p>This controller keeps ONLY the read-only {@link #list()} - the "Add Staff"/"Edit Staff" role
 * picker in {@code UsersPage.tsx} still needs to know each role's name to populate its dropdown
 * (that dropdown is actually a hardcoded {@code ROLE_NAMES} list client-side today and doesn't call
 * this at all, but nothing else in this codebase's history has depended on this GET disappearing,
 * so it's left in place as a harmless, still-useful read for any other caller - e.g. {@code
 * chefpay-javafx}'s own Role Management screen, out of scope for this release, which still reads
 * it). {@code ROLE_MANAGE} itself is no longer checked by anything reachable from a normal AppUser
 * session - it remains a real permission code (still assignable to a role) purely for backward
 * compatibility with any external caller that already checks for it, but grants no capability here
 * any more. */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleRepository roleRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW') or hasAuthority('USER_MANAGE') or hasAuthority('ROLE_MANAGE')")
    public ApiResponse<List<RoleDto>> list() {
        return ApiResponse.ok(roleRepository.findAll().stream().map(this::toDto).toList());
    }

    private RoleDto toDto(Role role) {
        List<String> codes = role.getPermissions().stream().map(Permission::getCode).sorted().toList();
        return new RoleDto(role.getId(), role.getName(), role.getDescription(), codes, role.getVersion());
    }
}
