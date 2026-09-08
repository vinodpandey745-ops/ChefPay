package com.chefpay.server.auth;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Device;
import com.chefpay.core.domain.DeviceType;
import com.chefpay.core.domain.Permission;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.DeviceRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.core.service.UserAccountService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String TERMINAL_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom TERMINAL_CODE_RANDOM = new SecureRandom();

    private final UserAccountService userAccountService;
    private final DeviceRepository deviceRepository;
    private final BranchRepository branchRepository;
    private final RestaurantRepository restaurantRepository;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final WebSocketEventPublisher eventPublisher;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        Optional<AppUser> user;
        LoginMethod loginMethod;
        if (request.password() != null && !request.password().isBlank()) {
            requireUsername(request);
            user = userAccountService.verifyPassword(request.username(), request.password());
            loginMethod = LoginMethod.PASSWORD;
        } else if (request.userCode() != null && !request.userCode().isBlank()) {
            // Phase 2 item 10: the deterministic path every current client uses - resolves to
            // exactly one candidate account by userCode before the PIN is even checked, so a
            // duplicate PIN across several staff can never cause an ambiguous login.
            user = userAccountService.verifyPinByUserCode(request.userCode(), request.pin());
            loginMethod = LoginMethod.PIN;
        } else if (request.pin() != null && !request.pin().isBlank()) {
            // Pre-Phase-2 fallback only - see LoginRequest's javadoc. Deliberately not what any
            // current client sends; kept so an install mid-upgrade (new server, old client build)
            // doesn't hard-fail every terminal at once.
            user = userAccountService.verifyPin(request.pin());
            loginMethod = LoginMethod.PIN;
        } else {
            throw ApiException.badRequest("MISSING_CREDENTIALS", "Provide either a password, or a user code + PIN.");
        }

        AppUser appUser = user.orElseThrow(() -> ApiException.unauthorized("Invalid credentials."));
        if (!appUser.isActive()) {
            throw ApiException.unauthorized("This account has been disabled. Contact an administrator.");
        }

        Device device = registerDevice(request, appUser);

        // Phase 2 items 14/32: a PIN login that resolved to a real device now has its
        // branch/terminal access checked - a password (Manager/Admin) login skips this, since it
        // isn't tied to a physical POS terminal the same way.
        if (loginMethod == LoginMethod.PIN && device.getId() != null) {
            if (device.getBranch() != null && !userAccountService.isAuthorizedForBranch(appUser, device.getBranch())) {
                throw ApiException.forbidden("Your account isn't authorized for this branch. "
                        + "Ask an administrator to grant access, or sign in at your usual branch.");
            }
            if (!userAccountService.isAuthorizedForTerminal(appUser, device)) {
                throw ApiException.forbidden("Your account isn't authorized for this terminal. "
                        + "Ask an administrator to grant access, or sign in at your usual terminal.");
            }
        }

        List<String> permissionCodes = appUser.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .toList();

        String token = jwtService.issueToken(appUser.getId(), appUser.getUsername(), appUser.getRole().getName(),
                permissionCodes, loginMethod);

        auditService.record(appUser.getId(), device.getId(), "AppUser", appUser.getId(), "USER_LOGGED_IN",
                null, null, null, CorrelationIdHolder.get());

        eventPublisher.publish("/topic/notifications", "USER_LOGGED_IN", appUser.getId(), appUser.getVersion(),
                Map.of("username", appUser.getUsername(), "displayName", appUser.getDisplayName(),
                        "role", appUser.getRole().getName(), "deviceName", device.getName()));

        List<LoginResponse.BranchSummary> effectiveBranches = effectiveBranches(appUser);
        UUID defaultBranchId = appUser.getDefaultBranch() == null ? null : appUser.getDefaultBranch().getId();

        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst().orElse(null);
        String organizationId = organizationIdOrGenerated(restaurant);
        String organizationName = restaurant == null || restaurant.getOrganizationName() == null
                || restaurant.getOrganizationName().isBlank()
                ? (restaurant == null ? null : restaurant.getName())
                : restaurant.getOrganizationName();

        LoginResponse.TerminalSummary terminal = new LoginResponse.TerminalSummary(
                device.getId(), device.getTerminalCode(), device.getName(),
                device.getBranch() == null ? null : device.getBranch().getId(),
                device.getBranch() == null ? null : device.getBranch().getName());

        LoginResponse response = new LoginResponse(
                token, appUser.getId(), appUser.getUsername(), appUser.getDisplayName(),
                appUser.getRole().getName(), permissionCodes, effectiveBranches, defaultBranchId,
                organizationId, organizationName, terminal, appUser.getUserCode(), loginMethod.name());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** Round 17: a blank {@code Restaurant#organizationId} is auto-filled (and persisted) the first
     * time anything actually reads it, rather than forcing a blocking "enter your org ID before you
     * can use the app" setup step - see {@code Restaurant#organizationId}'s javadoc. Every login
     * after the first for a given install sees the same generated value. */
    private String organizationIdOrGenerated(Restaurant restaurant) {
        if (restaurant == null) {
            return null;
        }
        if (restaurant.getOrganizationId() != null && !restaurant.getOrganizationId().isBlank()) {
            return restaurant.getOrganizationId();
        }
        String generated = "ORG-" + randomTerminalCode(8);
        restaurant.setOrganizationId(generated);
        restaurantRepository.save(restaurant);
        return generated;
    }

    /** See {@code LoginResponse}'s javadoc - an unassigned user (empty {@code AppUser.branches})
     * is unrestricted, so falls back to every branch that exists rather than an empty list (which
     * would otherwise make {@code ShellView}'s branch switcher disappear for every restaurant that
     * hasn't touched the new per-user branch assignment feature at all). */
    private List<LoginResponse.BranchSummary> effectiveBranches(AppUser appUser) {
        List<Branch> branches = appUser.getBranches().isEmpty()
                ? branchRepository.findAll()
                : List.copyOf(appUser.getBranches());
        return branches.stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(b -> new LoginResponse.BranchSummary(b.getId(), b.getName()))
                .toList();
    }

    private void requireUsername(LoginRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            throw ApiException.badRequest("MISSING_USERNAME", "Username is required for password login.");
        }
    }

    /** Round 17: prefers looking the terminal up by its persisted {@code terminalCode} (stable
     * across a rename) over the old by-name lookup, which only kicks in for a terminal that has
     * never completed Terminal Setup (no code yet) - see {@code LoginRequest#terminalCode}'s
     * javadoc. Every registered/re-registered terminal ends this method with a non-blank
     * {@code terminalCode}, generating one on the spot for any pre-Round-17 Device row or a client
     * that didn't supply one. */
    private Device registerDevice(LoginRequest request, AppUser user) {
        String deviceName = (request.deviceName() == null || request.deviceName().isBlank())
                ? "unnamed-device"
                : request.deviceName();
        DeviceType type = parseDeviceType(request.deviceType());

        Device device = null;
        // Phase 2: a client that already completed the branch -> terminal-select first-run flow
        // knows the exact Device row id directly - preferred over the terminalCode/deviceName
        // heuristics below, which exist only for a client that hasn't gone through that flow yet
        // (see LoginRequest#terminalId's javadoc). An id that doesn't resolve to a real row falls
        // through to those heuristics rather than failing outright, since a stale locally-cached id
        // (e.g. the terminal was deleted from the admin screen since) shouldn't hard-block login.
        if (request.terminalId() != null) {
            device = deviceRepository.findById(request.terminalId()).orElse(null);
        }
        if (device == null && request.terminalCode() != null && !request.terminalCode().isBlank()) {
            device = deviceRepository.findByTerminalCodeIgnoreCase(request.terminalCode()).orElse(null);
        }
        if (device == null) {
            device = deviceRepository.findByNameIgnoreCase(deviceName).orElseGet(() ->
                    Device.builder().name(deviceName).type(type).active(true).build());
        }
        // Round 17: an existing (already-persisted) terminal an admin has retired via the
        // Branches & Terminals screen must not be able to keep signing staff in - see
        // Device#active's javadoc. A brand-new (not-yet-persisted) row is never inactive at this
        // point (the builder above always sets active=true), so this only ever rejects a real,
        // previously-registered-then-disabled terminal.
        if (device.getId() != null && !device.isActive()) {
            throw ApiException.forbidden("This terminal has been deactivated. Ask an admin to reactivate it "
                    + "from the Branches & Terminals screen, or sign in from a different terminal.");
        }
        device.setType(type);
        device.setLastUser(user);
        device.setLastSeenAt(LocalDateTime.now());
        if (device.getTerminalCode() == null || device.getTerminalCode().isBlank()) {
            device.setTerminalCode("T-" + randomTerminalCode(4));
        }
        if (request.branchId() != null) {
            branchRepository.findById(request.branchId()).ifPresent(device::setBranch);
        }
        return deviceRepository.save(device);
    }

    private static String randomTerminalCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(TERMINAL_CODE_ALPHABET.charAt(TERMINAL_CODE_RANDOM.nextInt(TERMINAL_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private DeviceType parseDeviceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return DeviceType.JAVAFX_POS;
        }
        try {
            return DeviceType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return DeviceType.JAVAFX_POS;
        }
    }
}
