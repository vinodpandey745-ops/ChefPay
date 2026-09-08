package com.chefpay.server.purchasing;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Supplier;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.SupplierRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Plain offline/manual supplier directory (Round 12 §12-§18). See {@link Supplier}'s javadoc for
 * why there's no API-integration surface here yet - {@link SupplierChannel} is the seam a future
 * integration hooks into without this entity or service changing shape.
 *
 * <p>Bistrodesk branch-isolation release (requirement #6): a supplier belongs to exactly one
 * branch now - {@link #listSuppliers} filters to the caller's accessible branches (same shape as
 * {@code InventoryService#listItems}, minus its null-branch/shared-item tolerance, since a
 * supplier is never a legitimate shared row here - see {@link Supplier#getBranch()}'s javadoc),
 * {@link #createSupplier} takes an already-resolved {@link Branch}, and {@link #updateSupplier}
 * asserts branch access before editing (same {@code assertItemAccess} pattern
 * {@code InventoryService} established).
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final AppUserRepository appUserRepository;
    private final BranchAccessService branchAccessService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<Supplier> listSuppliers(Set<UUID> accessibleBranchIds) {
        if (accessibleBranchIds == null) {
            return supplierRepository.findByActiveTrueOrderByNameAsc();
        }
        if (accessibleBranchIds.size() == 1) {
            return supplierRepository.findByBranch_IdAndActiveTrueOrderByNameAsc(accessibleBranchIds.iterator().next());
        }
        return supplierRepository.findByBranch_IdInAndActiveTrueOrderByNameAsc(accessibleBranchIds);
    }

    @Transactional
    public Supplier createSupplier(String name, String contactPerson, String phone, String email,
                                    String address, String notes, Branch branch, UUID actorUserId) {
        if (supplierRepository.existsByNameIgnoreCaseAndBranch_Id(name, branch.getId())) {
            throw ApiException.badRequest("DUPLICATE_SUPPLIER", "A supplier named '" + name + "' already exists.");
        }
        Supplier saved = supplierRepository.save(Supplier.builder()
                .name(name).contactPerson(contactPerson).phone(phone).email(email).address(address).notes(notes)
                .branch(branch)
                .build());
        auditService.record(actorUserId, null, "Supplier", saved.getId(), "CREATE", null, saved.getName(), null, CorrelationIdHolder.get());
        return saved;
    }

    @Transactional
    public Supplier updateSupplier(UUID id, String name, String contactPerson, String phone, String email,
                                    String address, String notes, Boolean active, long version, UUID actorUserId) {
        Supplier supplier = supplierRepository.findById(id).orElseThrow(() -> ApiException.notFound("Supplier not found"));
        assertSupplierAccess(supplier, actorUserId);
        if (supplier.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(Supplier.class, id);
        }
        if (name != null) supplier.setName(name);
        if (contactPerson != null) supplier.setContactPerson(contactPerson);
        if (phone != null) supplier.setPhone(phone);
        if (email != null) supplier.setEmail(email);
        if (address != null) supplier.setAddress(address);
        if (notes != null) supplier.setNotes(notes);
        if (active != null) supplier.setActive(active);
        Supplier saved = supplierRepository.save(supplier);
        auditService.record(actorUserId, null, "Supplier", saved.getId(), "UPDATE", null, null, null, CorrelationIdHolder.get());
        return saved;
    }

    private void assertSupplierAccess(Supplier supplier, UUID actorUserId) {
        Branch branch = supplier.getBranch();
        if (branch == null) {
            return;
        }
        AppUser requester = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
        branchAccessService.assertAccess(requester, branch.getId());
    }
}
