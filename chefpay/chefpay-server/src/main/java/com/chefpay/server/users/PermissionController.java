package com.chefpay.server.users;

import com.chefpay.core.domain.Permission;
import com.chefpay.core.repository.PermissionRepository;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** Master permission catalog. Bistrodesk follow-up requirement #7 ("move the role and permission
 * to admin portal which can be modified only by bistrodesk team or admin. remove completely from
 * application") removed the POS app's own Role &amp; Permission management screen entirely - the
 * equivalent checklist now lives in the platform-owner-key-gated admin console (see {@code
 * PlatformOwnerController#listPermissions}). This endpoint is left in place, still gated on {@code
 * ROLE_MANAGE}, purely because {@code chefpay-javafx}'s own (out of scope this release) Role
 * Management screen still reads it to populate its permission checklist - see {@code
 * com.chefpay.server.users.RoleController}'s javadoc for the identical reasoning applied to {@code
 * GET /api/roles}. Nothing reachable from a normal AppUser session in {@code chefpay-web} calls
 * this any more. */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionRepository permissionRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ApiResponse<List<PermissionDto>> list() {
        return ApiResponse.ok(permissionRepository.findAll().stream()
                .sorted(Comparator.comparing(Permission::getCode))
                .map(this::toDto)
                .toList());
    }

    private PermissionDto toDto(Permission permission) {
        return new PermissionDto(permission.getId(), permission.getCode(), permission.getDescription());
    }
}
