package com.chefpay.server.branches;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Device;
import com.chefpay.core.domain.DeviceType;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.DeviceRepository;
import com.chefpay.core.repository.FloorRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.terminals.TerminalDto;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2 (items 5-7): real Branch CRUD - previously the only way to get a {@code Branch} row was
 * indirectly via {@code RestaurantController#createBranch} (no code, no activate/deactivate, no
 * list-with-terminal-count). This controller is the Manager/Admin "Branches" screen's backend, plus
 * the Terminal bulk/single-create endpoints (item 7's "how many terminals do you want?" prompt) -
 * kept here rather than in {@code TerminalController} because the design's own path shape
 * ({@code /api/branches/{id}/terminals...}) is naturally branch-scoped.
 *
 * <p>Every mutating endpoint requires {@code BRANCH_MANAGE}/{@code TERMINAL_MANAGE} AND a
 * password-authenticated session ({@link AuthenticatedPrincipal#requirePasswordLogin()}) - see
 * PHASE2_ORG_SUBSCRIPTION_DESIGN.md Section F for why this is enforced twice (permission +
 * login-method), not once.
 *
 * <p>Bistrodesk branch-isolation release (requirement #2, user-confirmed decision): branch
 * *creation* is no longer reachable from here (or from anywhere in the POS app) at all - see
 * {@code com.chefpay.server.platform.PlatformOwnerController#createBranch} for the one remaining
 * path, gated by the platform-owner key rather than any POS role's permissions. This controller
 * still owns editing an existing branch's profile and its terminals, per that same decision.
 */
@RestController
@RequestMapping("/api/branches")
@RequiredArgsConstructor
public class BranchController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BranchRepository branchRepository;
    private final FloorRepository floorRepository;
    private final DeviceRepository deviceRepository;
    private final AuditService auditService;
    private final BranchAccessService branchAccessService;

    /** Bistrodesk follow-up requirement #8 ("Branches and terminal option should show only logged
     * in branch not all the branches across"): this used to return every branch on the install
     * unconditionally, gated only by permission - so a branch-scoped Manager (holding {@code
     * BRANCH_MANAGE}/{@code TERMINAL_MANAGE} for day-to-day terminal upkeep, but assigned to only
     * one branch) could see, and - since {@link #update}/{@link #delete}/the terminal endpoints
     * below had no per-branch check either - edit every OTHER branch's profile and terminals too.
     * Now filtered through {@link BranchAccessService#accessibleBranchIds}, same as every other
     * branch-scoped list in this codebase: an unrestricted caller (OWNER/ADMIN, or any account
     * holding {@link BranchAccessService#VIEW_ALL_BRANCHES}) still sees every branch (the
     * legitimate multi-branch-owner case this screen also serves), a branch-restricted caller sees
     * only their own assigned branch(es).
     *
     * <p>Bistrodesk follow-up requirement #10 (query optimization pass): {@link #toDto} needs each
     * branch's terminal count, which used to mean one {@code countByBranchId} round-trip per branch
     * inside the {@code .map(this::toDto)} below (N+1 - fine for one branch, wasteful for an install
     * with many). {@link DeviceRepository#countGroupedByBranch} fetches every branch's count in one
     * query up front, and {@link #toDto(Branch, long)} below takes the pre-fetched count instead of
     * looking it up itself. */
    @GetMapping
    @PreAuthorize("hasAuthority('BRANCH_MANAGE') or hasAuthority('USER_MANAGE') or hasAuthority('TERMINAL_MANAGE')")
    public ApiResponse<List<BranchDto>> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> allowed = branchAccessService.accessibleBranchIds(principal);
        java.util.Map<UUID, Long> terminalCounts = deviceRepository.countGroupedByBranch().stream()
                .collect(java.util.stream.Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        return ApiResponse.ok(branchRepository.findAllByOrderByNameAsc().stream()
                .filter(b -> allowed == null || allowed.contains(b.getId()))
                .map(b -> toDto(b, terminalCounts.getOrDefault(b.getId(), 0L)))
                .toList());
    }

    /** Bistrodesk Phase 5: the branch-switcher's data source for Dashboard/Reports - deliberately
     * NOT gated on {@code BRANCH_MANAGE}/{@code USER_MANAGE}/{@code TERMINAL_MANAGE} like {@link
     * #list()} above, since a Cashier/Manager with only {@code DASHBOARD_VIEW}/{@code REPORT_VIEW}
     * still needs to know which branch(es) they're looking at - any authenticated user may call
     * this. Pre-filtered to exactly what {@link BranchAccessService#accessibleBranchIds} already
     * returns for this caller (every branch for an unrestricted user, just their own assigned set
     * otherwise) - see {@link AccessibleBranchDto}'s javadoc for why this is safe with no
     * permission gate. */
    @GetMapping("/accessible")
    public ApiResponse<List<AccessibleBranchDto>> accessible(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> allowed = branchAccessService.accessibleBranchIds(principal);
        List<Branch> branches = branchRepository.findAllByOrderByNameAsc().stream()
                .filter(Branch::isActive)
                .filter(b -> allowed == null || allowed.contains(b.getId()))
                .toList();
        return ApiResponse.ok(branches.stream().map(b -> new AccessibleBranchDto(b.getId(), b.getName())).toList());
    }

    /** Public (no auth) - the POS client's first-run "enter your branch code" screen. Returns 404
     * with a clear message on any miss (unknown code OR a deactivated branch) rather than ever
     * distinguishing the two - see {@code Branch#active}'s javadoc; a deactivated branch must look
     * exactly like a nonexistent one to an unauthenticated caller. */
    @GetMapping("/by-code/{code}")
    public ApiResponse<BranchByCodeResponse> byCode(@PathVariable String code) {
        Branch branch = branchRepository.findByBranchCodeIgnoreCase(code)
                .filter(Branch::isActive)
                .orElseThrow(() -> ApiException.notFound(
                        "No active branch found for that code. Check with your manager and try again."));
        return ApiResponse.ok(new BranchByCodeResponse(branch.getId(), branch.getName(), branch.isActive()));
    }

    /** Public (no auth) - lets the POS first-run flow skip the Branch Code screen entirely when
     * there is exactly one active branch (the common single-location install), matching this
     * design's own "a single-branch, single-terminal restaurant never sees an extra screen"
     * principle - the Terminal Select step already auto-skips this way when there's exactly one
     * terminal, but the Branch Code step never had the equivalent check. Deliberately returns 404
     * (never 200-with-null/empty) whenever there isn't exactly one active branch, so every client
     * implements the same "try /default first, fall back to manual code entry on ANY failure" -
     * no special-cased zero-branch vs multi-branch handling anywhere. */
    @GetMapping("/default")
    public ApiResponse<BranchByCodeResponse> defaultBranch() {
        List<Branch> active = branchRepository.findAllByOrderByNameAsc().stream()
                .filter(Branch::isActive)
                .toList();
        if (active.size() != 1) {
            throw ApiException.notFound("No single default branch - pick one by code.");
        }
        Branch branch = active.get(0);
        return ApiResponse.ok(new BranchByCodeResponse(branch.getId(), branch.getName(), branch.isActive()));
    }

    /** Edit name/address/phone, or activate/deactivate. Deactivating (setting {@code active=false})
     * is blocked while the branch still has an active terminal - same "explain why, don't silently
     * orphan something" convention as the existing category-delete conflict message - an admin must
     * deactivate/reassign every terminal first. Reactivating has no such restriction. */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('BRANCH_MANAGE')")
    @Transactional
    public ApiResponse<BranchDto> update(@PathVariable UUID id, @RequestBody UpdateBranchRequest request,
                                          @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        branchAccessService.assertAccess(principal, id);
        Branch branch = branchRepository.findById(id).orElseThrow(() -> ApiException.notFound("Branch not found"));
        if (branch.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(Branch.class, id);
        }
        if (request.name() != null && !request.name().isBlank()) {
            branch.setName(request.name());
        }
        if (request.address() != null) {
            branch.setAddress(request.address());
        }
        if (request.phone() != null) {
            branch.setPhone(request.phone());
        }
        // Bistrodesk branch-isolation release (requirement #4): empty string clears a previously-set
        // value, same convention UpdateRestaurantRequest#logoImageBase64 already uses - null alone
        // means "unchanged".
        if (request.gstin() != null) {
            branch.setGstin(request.gstin());
        }
        if (request.supportPhone() != null) {
            branch.setSupportPhone(request.supportPhone());
        }
        if (request.receiptFooterText() != null) {
            branch.setReceiptFooterText(request.receiptFooterText());
        }
        if (request.logoImageBase64() != null) {
            branch.setLogoImageBase64(request.logoImageBase64());
        }
        // Follow-up requirement #4 (WhatsApp integration): same null-means-unchanged, empty-string-
        // clears convention as gstin/supportPhone/etc above. whatsappApiKey is write-only - never
        // read back in BranchDto, see BranchDto#whatsappApiKeyConfigured.
        if (request.whatsappProvider() != null) {
            branch.setWhatsappProvider(request.whatsappProvider());
        }
        if (request.whatsappSenderNumber() != null) {
            branch.setWhatsappSenderNumber(request.whatsappSenderNumber());
        }
        if (request.whatsappApiKey() != null) {
            branch.setWhatsappApiKey(request.whatsappApiKey());
        }
        if (request.whatsappAccountId() != null) {
            branch.setWhatsappAccountId(request.whatsappAccountId());
        }
        // Follow-up enhancement ("Local Time Zone During Branch Creation"): same null-means-
        // unchanged convention; a blank string is treated as "no real selection" and ignored rather
        // than ever leaving the branch with an empty zone (see Branch#getTimezone()'s javadoc - this
        // field always has a real value once backfilled).
        if (request.timezone() != null && !request.timezone().isBlank()) {
            branch.setTimezone(request.timezone());
        }
        // POS patch (manual KOT print and order completion) - see Branch#manualKotPrintEnabled's
        // javadoc. Same null-means-unchanged convention as every other field on this request.
        if (request.manualKotPrintEnabled() != null) {
            branch.setManualKotPrintEnabled(request.manualKotPrintEnabled());
        }
        if (request.active() != null && !request.active() && branch.isActive()) {
            long activeTerminals = deviceRepository.findByBranchIdAndActiveTrueOrderBySequenceNoAsc(id).size();
            if (activeTerminals > 0) {
                throw ApiException.conflict("BRANCH_HAS_ACTIVE_TERMINALS",
                        "This branch still has " + activeTerminals + " active terminal(s). "
                                + "Deactivate or reassign them first.");
            }
        }
        if (request.active() != null) {
            branch.setActive(request.active());
        }
        Branch saved = branchRepository.save(branch);
        auditService.record(principal.userId(), null, "Branch", saved.getId(),
                request.active() != null && !request.active() ? "BRANCH_DEACTIVATED" : "BRANCH_UPDATED",
                null, null, null, CorrelationIdHolder.get());
        return ApiResponse.ok(toDto(saved));
    }

    /** Hard-delete, only when provably empty (no terminals at all, active or not, and no
     * floors/tables) - a branch with any of those has real historical association a delete would
     * either orphan or block on a foreign-key constraint anyway, so this checks up front for a
     * clear message rather than surfacing a raw database error. Deliberately does not also check
     * for historical orders (no branch-scoped order query exists yet) - a branch with zero
     * terminals ever registered and zero floors/tables could never have taken an order in this
     * codebase's data model, so that check would always be redundant with the ones already here. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('BRANCH_MANAGE')")
    @Transactional
    public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        branchAccessService.assertAccess(principal, id);
        Branch branch = branchRepository.findById(id).orElseThrow(() -> ApiException.notFound("Branch not found"));
        long terminalCount = deviceRepository.countByBranchId(id);
        if (terminalCount > 0) {
            throw ApiException.conflict("BRANCH_NOT_EMPTY",
                    "This branch has " + terminalCount + " terminal(s) registered. Remove them first.");
        }
        // Bistrodesk follow-up requirement #10 (query optimization pass): this used to fetch every
        // floor on the whole install just to check whether this ONE branch has any - now uses the
        // branch-scoped finder FloorRepository#findByBranch_IdOrderByDisplayOrderAsc already added
        // for requirement #5's table-seeding work, which the database can serve as a single indexed
        // lookup instead of a full table scan filtered in memory.
        long floorCount = floorRepository.findByBranch_IdOrderByDisplayOrderAsc(id).size();
        if (floorCount > 0) {
            throw ApiException.conflict("BRANCH_NOT_EMPTY",
                    "This branch still has floors/tables configured. Remove them first.");
        }
        branchRepository.delete(branch);
        auditService.record(principal.userId(), null, "Branch", id, "BRANCH_DELETED",
                branch.getName(), null, null, CorrelationIdHolder.get());
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/terminals")
    public ApiResponse<List<TerminalDto>> listTerminals(@PathVariable UUID id,
                                                          @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, id);
        branchRepository.findById(id).orElseThrow(() -> ApiException.notFound("Branch not found"));
        return ApiResponse.ok(deviceRepository.findByBranchIdAndActiveTrueOrderBySequenceNoAsc(id).stream()
                .map(this::toTerminalDto).toList());
    }

    /** Item 7's "how many terminals do you want?" prompt - creates {@code request.count()}
     * terminals in one call, numbered sequentially continuing from this branch's current highest
     * {@code sequence_no} (never restarting at 1 if some already exist). */
    @PostMapping("/{id}/terminals:bulk")
    @PreAuthorize("hasAuthority('TERMINAL_MANAGE')")
    @Transactional
    public ApiResponse<List<TerminalDto>> bulkCreateTerminals(@PathVariable UUID id,
                                                                @Valid @RequestBody BulkCreateTerminalsRequest request,
                                                                @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        branchAccessService.assertAccess(principal, id);
        Branch branch = branchRepository.findById(id).orElseThrow(() -> ApiException.notFound("Branch not found"));
        Integer highest = deviceRepository.findMaxSequenceNoForBranch(id);
        int next = (highest == null ? 0 : highest) + 1;
        String prefix = (request.namePrefix() == null || request.namePrefix().isBlank())
                ? "Terminal" : request.namePrefix().trim();
        List<TerminalDto> created = new java.util.ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            Device device = Device.builder()
                    .name(prefix + " " + String.format("%03d", next))
                    .type(DeviceType.JAVAFX_POS)
                    .branch(branch)
                    .active(true)
                    .sequenceNo(next)
                    .terminalCode("T-" + randomTerminalCode(4))
                    .build();
            created.add(toTerminalDto(deviceRepository.save(device)));
            next++;
        }
        auditService.record(principal.userId(), null, "Branch", id, "TERMINAL_CREATED",
                null, request.count() + " terminal(s) added to " + branch.getName(), null, CorrelationIdHolder.get());
        return ApiResponse.ok(created);
    }

    @PostMapping("/{id}/terminals")
    @PreAuthorize("hasAuthority('TERMINAL_MANAGE')")
    @Transactional
    public ApiResponse<TerminalDto> createTerminal(@PathVariable UUID id, @RequestBody(required = false) CreateTerminalRequest request,
                                                     @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        principal.requirePasswordLogin();
        branchAccessService.assertAccess(principal, id);
        Branch branch = branchRepository.findById(id).orElseThrow(() -> ApiException.notFound("Branch not found"));
        Integer highest = deviceRepository.findMaxSequenceNoForBranch(id);
        int next = (highest == null ? 0 : highest) + 1;
        String name = (request == null || request.name() == null || request.name().isBlank())
                ? "Terminal " + String.format("%03d", next) : request.name().trim();
        Device device = Device.builder()
                .name(name)
                .type(DeviceType.JAVAFX_POS)
                .branch(branch)
                .active(true)
                .sequenceNo(next)
                .terminalCode("T-" + randomTerminalCode(4))
                .build();
        Device saved = deviceRepository.save(device);
        auditService.record(principal.userId(), null, "Branch", id, "TERMINAL_CREATED",
                null, saved.getName(), null, CorrelationIdHolder.get());
        return ApiResponse.ok(toTerminalDto(saved));
    }

    /** Single-branch case (e.g. {@link #update}'s response, right after editing exactly one row) -
     * one {@code countByBranchId} lookup here is unavoidable and cheap; {@link #list} instead uses
     * {@link #toDto(Branch, long)} with a pre-fetched count to avoid paying this once per row. */
    private BranchDto toDto(Branch branch) {
        return toDto(branch, deviceRepository.countByBranchId(branch.getId()));
    }

    private BranchDto toDto(Branch branch, long terminalCount) {
        return new BranchDto(branch.getId(), branch.getName(), branch.getBranchCode(), branch.getAddress(),
                branch.getPhone(), branch.isActive(), terminalCount, branch.getVersion(),
                branch.getGstin(), branch.getSupportPhone(), branch.getReceiptFooterText(), branch.getLogoImageBase64(),
                branch.getWhatsappProvider(), branch.getWhatsappSenderNumber(), branch.getWhatsappAccountId(),
                branch.getWhatsappApiKey() != null && !branch.getWhatsappApiKey().isBlank(),
                branch.getTimezone(), branch.isManualKotPrintEnabled());
    }

    private TerminalDto toTerminalDto(Device device) {
        Branch branch = device.getBranch();
        return new TerminalDto(
                device.getId(), device.getName(), device.getTerminalCode(),
                device.getType() == null ? null : device.getType().name(),
                branch == null ? null : branch.getId(),
                branch == null ? null : branch.getName(),
                device.isActive(),
                device.getLastUser() == null ? null : device.getLastUser().getDisplayName(),
                device.getLastSeenAt(),
                device.getVersion(),
                device.getSequenceNo());
    }

    private static final String TERMINAL_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private String randomTerminalCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(TERMINAL_CODE_ALPHABET.charAt(RANDOM.nextInt(TERMINAL_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
