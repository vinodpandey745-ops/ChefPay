package com.chefpay.server.customers;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Customer;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.CustomerRepository;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Guest directory (Phase 5c) - see {@code Customer}'s javadoc for why this is a standalone CRM
 * list rather than a foreign key off {@code Order}. {@code search} backs both the Customers
 * screen's search box and the Delivery/Pickup quick-order dialog's phone lookup - same query
 * string matched against phone OR name.
 *
 * <p>Bistrodesk branch-isolation release (requirement #6): a customer belongs to exactly one
 * branch now (see {@link Customer#getBranch()}'s javadoc). {@code create()} resolves the effective
 * branch the same mandatory way {@code Order}/{@code Subscription} do -
 * {@link BranchAccessService#resolveEffectiveBranchId} - never leaving a new row branchless;
 * {@code update()} access-checks the existing row's branch first, same shape as every other
 * branch-scoped controller in this release.
 *
 * <p>Bistrodesk post-release fix: {@code list()} previously went straight to {@code
 * accessibleBranchIds}, which is {@code null} - no filter, every branch's customers merged - for
 * any unrestricted caller (an Owner/Admin account with no branch assignments, or anyone holding
 * {@code VIEW_ALL_BRANCHES}) even though {@code create()} was always correctly branch-scoped -
 * confirmed real-world bug ("a customer created in branch A is visible from other branches").
 * {@code branchId} is now a real, optional, access-checked query param, and an omitted one resolves
 * this caller's ONE working branch first (mirroring {@code TableController#list}/{@code
 * OrderController#resolveAccessibleBranchIds}'s identical fallback chain) before falling back to
 * "every accessible branch" only when that's genuinely ambiguous ({@code BRANCH_REQUIRED}). A
 * branch-restricted caller was already correctly scoped and sees no change. */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final BranchAccessService branchAccessService;

    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW') or hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<List<CustomerDto>> list(@RequestParam(required = false) String query,
                                                @RequestParam(required = false) UUID branchId,
                                                @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        Set<UUID> accessible = resolveBranchFilter(branchId, requester);
        boolean blank = query == null || query.isBlank();
        List<Customer> customers;
        if (accessible == null) {
            customers = blank ? customerRepository.findAllByOrderByNameAsc()
                    : legacySearch(query);
        } else if (accessible.size() == 1) {
            UUID oneBranchId = accessible.iterator().next();
            customers = blank ? customerRepository.findByBranch_IdOrderByNameAsc(oneBranchId)
                    : customerRepository.searchByBranch(oneBranchId, query);
        } else {
            customers = blank ? customerRepository.findByBranch_IdInOrderByNameAsc(accessible)
                    : customerRepository.searchByBranches(accessible, query);
        }
        return ApiResponse.ok(customers.stream().map(this::toDto).toList());
    }

    /** Same fallback chain as {@code OrderController#resolveAccessibleBranchIds} - see this
     * controller's own javadoc for why the Customer directory needs it too. */
    private Set<UUID> resolveBranchFilter(UUID requestedBranchId, AppUser requester) {
        if (requestedBranchId != null) {
            branchAccessService.assertAccess(requester, requestedBranchId);
            return Set.of(requestedBranchId);
        }
        try {
            return Set.of(branchAccessService.resolveEffectiveBranchId(requester, null));
        } catch (ApiException ex) {
            if (!"BRANCH_REQUIRED".equals(ex.getErrorCode())) {
                throw ex;
            }
            return branchAccessService.accessibleBranchIds(requester);
        }
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<CustomerDto> create(@Valid @RequestBody CreateCustomerRequest request,
                                            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        UUID branchId = branchAccessService.resolveEffectiveBranchId(requester, request.branchId());
        Branch branch = branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        Customer customer = Customer.builder()
                .name(request.name())
                .phone(request.phone())
                .email(request.email())
                .notes(request.notes())
                .branch(branch)
                .build();
        return ApiResponse.ok(toDto(customerRepository.save(customer)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    @Transactional
    public ApiResponse<CustomerDto> update(@PathVariable UUID id, @RequestBody UpdateCustomerRequest request,
                                            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Customer customer = customerRepository.findById(id).orElseThrow(() -> ApiException.notFound("Customer not found"));
        if (customer.getBranch() != null) {
            branchAccessService.assertAccess(branchAccessService.resolve(principal), customer.getBranch().getId());
        }
        if (customer.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(Customer.class, id);
        }
        if (request.name() != null) customer.setName(request.name());
        if (request.phone() != null) customer.setPhone(request.phone());
        if (request.email() != null) customer.setEmail(request.email());
        if (request.notes() != null) customer.setNotes(request.notes());
        return ApiResponse.ok(toDto(customerRepository.save(customer)));
    }

    /** Pre-backfill defensive path only (see {@code DataSeeder}) - an unrestricted caller with the
     * old unfiltered phone/name search, used only if a request lands before startup backfill has
     * run. Once every row has a branch this is behaviourally identical to a plain global search. */
    private List<Customer> legacySearch(String query) {
        return customerRepository.findByPhoneContainingOrNameContainingIgnoreCaseOrderByNameAsc(query, query);
    }

    private CustomerDto toDto(Customer c) {
        return new CustomerDto(c.getId(), c.getName(), c.getPhone(), c.getEmail(), c.getNotes(),
                c.getVisitCount(), c.getTotalSpend(), c.getLastVisitAt(), c.getVersion(),
                c.getBranch() == null ? null : c.getBranch().getId(),
                c.getBranch() == null ? null : c.getBranch().getName());
    }
}
