package com.chefpay.server.inventory;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.InventoryItem;
import com.chefpay.core.domain.InventoryTransaction;
import com.chefpay.core.domain.InventoryTransactionType;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.InventoryItemRepository;
import com.chefpay.core.repository.InventoryTransactionRepository;
import com.chefpay.core.repository.SupplierRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the ledger discipline {@link InventoryService#recordTransaction} enforces: quantity moves
 * in the direction implied by {@link InventoryTransactionType}, a would-go-negative deduction/waste
 * is rejected rather than clamped or silently allowed, and every write requires a resolvable actor
 * (mirrors {@code BillingServiceTest}'s cash-movement-actor coverage) and an up-to-date item
 * version (optimistic locking, same as every other mutable entity in this codebase).
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryItemRepository itemRepository;

    @Mock
    private InventoryTransactionRepository transactionRepository;

    @Mock
    private AppUserRepository appUserRepository;

    // Round 14 (F3.1): InventoryService gained a preferred-supplier lookup dependency for
    // InventoryItem#preferredSupplier - see InventoryService#setPreferredSupplier. Not exercised by
    // any test in this class, but the constructor call below still needs a mock for it (same class
    // of stale-constructor issue Round 13's BillingServiceTest hit - see that fix's comment there).
    @Mock
    private SupplierRepository supplierRepository;

    // Bistrodesk branch-isolation release: InventoryService gained branch-isolation dependencies
    // (branchRepository to resolve an item's branch on create, branchAccessService to gate
    // mutations on the caller's access) back in Phase 2 - same stale-constructor consideration as
    // supplierRepository's note above. Branch is now mandatory on create (see
    // InventoryService#createItem's javadoc), so createItem tests below resolve a real branch;
    // every other test here still creates items with no branch (pre-existing rows/direct builder
    // use), so BranchAccessService's checks stay a no-op throughout for them (see
    // InventoryService#assertItemAccess's null-branch short-circuit).
    @Mock
    private BranchRepository branchRepository;

    @Mock
    private BranchAccessService branchAccessService;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    private InventoryService inventoryService;

    private AppUser actor;

    private Branch branch;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(itemRepository, transactionRepository, appUserRepository, supplierRepository,
                branchRepository, branchAccessService, auditService, notificationService);
        actor = AppUser.builder().username("cashier1").displayName("Cashier One").build();
        setId(actor, UUID.randomUUID());
        branch = Branch.builder().name("Main Branch").build();
        setId(branch, UUID.randomUUID());
    }

    @Test
    void createItemRejectsDuplicateNameCaseInsensitively() {
        when(branchRepository.findById(branch.getId())).thenReturn(Optional.of(branch));
        when(itemRepository.existsByNameIgnoreCaseAndBranchId("Basmati Rice", branch.getId())).thenReturn(true);

        assertThatThrownBy(() -> inventoryService.createItem("Basmati Rice", "kg", BigDecimal.TEN, null, null, branch.getId(), actor.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");

        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItemRejectsAMissingBranch() {
        assertThatThrownBy(() -> inventoryService.createItem("Sugar", "kg", null, null, null, null, actor.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must belong to a branch");

        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItemDefaultsNullOpeningQuantityToZero() {
        when(branchRepository.findById(branch.getId())).thenReturn(Optional.of(branch));
        when(itemRepository.existsByNameIgnoreCaseAndBranchId(any(), any())).thenReturn(false);
        when(itemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InventoryItem saved = inventoryService.createItem("Sugar", "kg", null, new BigDecimal("2.000"), null, branch.getId(), actor.getId());

        assertThat(saved.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getUnit()).isEqualTo("kg");
        assertThat(saved.getBranch()).isEqualTo(branch);
    }

    @Test
    void updateItemRejectsStaleVersion() {
        InventoryItem item = item("Sugar", "5.000", "2.000");
        item.setVersion(3L);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> inventoryService.updateItem(item.getId(), "Sugar", "kg", null, null, null, 2L, actor.getId()))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(itemRepository, never()).save(any());
    }

    @Test
    void receiveTransactionIncreasesQuantityAndRecordsResultingBalance() {
        InventoryItem item = item("Rice", "10.000", "5.000");
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(appUserRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InventoryTransaction txn = inventoryService.recordTransaction(item.getId(), "RECEIVE", new BigDecimal("15.000"),
                "Weekly delivery", item.getVersion(), actor.getId());

        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("25.000");
        assertThat(txn.getResultingQuantity()).isEqualByComparingTo("25.000");
        assertThat(txn.getType()).isEqualTo(InventoryTransactionType.RECEIVE);
        verify(itemRepository).save(item);
    }

    @Test
    void deductTransactionDecreasesQuantity() {
        InventoryItem item = item("Rice", "10.000", "5.000");
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(appUserRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.recordTransaction(item.getId(), "DEDUCT", new BigDecimal("4.000"), "Used in prep",
                item.getVersion(), actor.getId());

        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("6.000");
    }

    @Test
    void wasteTransactionThatWouldGoNegativeIsRejected() {
        InventoryItem item = item("Rice", "3.000", "5.000");
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(appUserRepository.findById(actor.getId())).thenReturn(Optional.of(actor));

        assertThatThrownBy(() -> inventoryService.recordTransaction(item.getId(), "WASTE", new BigDecimal("5.000"),
                "Spoiled", item.getVersion(), actor.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("below zero");

        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("3.000");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transactionWithoutAResolvableActorIsRejected() {
        InventoryItem item = item("Rice", "10.000", "5.000");
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(appUserRepository.findById(actor.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.recordTransaction(item.getId(), "RECEIVE", BigDecimal.ONE,
                "reason", item.getVersion(), actor.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("logged-in user");
    }

    @Test
    void unknownTransactionTypeIsRejected() {
        InventoryItem item = item("Rice", "10.000", "5.000");
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> inventoryService.recordTransaction(item.getId(), "NOT_A_TYPE", BigDecimal.ONE,
                "reason", item.getVersion(), actor.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown inventory transaction type");
    }

    @Test
    void isLowStockComparesAgainstThresholdOnlyWhenSet() {
        InventoryItem noThreshold = item("Oil", "1.000", null);
        InventoryItem atThreshold = item("Salt", "2.000", "2.000");
        InventoryItem aboveThreshold = item("Pepper", "9.000", "2.000");

        assertThat(inventoryService.isLowStock(noThreshold)).isFalse();
        assertThat(inventoryService.isLowStock(atThreshold)).isTrue();
        assertThat(inventoryService.isLowStock(aboveThreshold)).isFalse();
    }

    @Test
    void recordTransactionCapturesAuditEntry() {
        InventoryItem item = item("Rice", "10.000", "5.000");
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(appUserRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.recordTransaction(item.getId(), "ADJUST", new BigDecimal("1.500"), "Stock count correction",
                item.getVersion(), actor.getId());

        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(any(), any(), any(), any(), actionCaptor.capture(), any(), any(), any(), any());
        assertThat(actionCaptor.getValue()).isEqualTo("STOCK_ADJUST");
    }

    private InventoryItem item(String name, String quantity, String threshold) {
        InventoryItem item = InventoryItem.builder()
                .name(name)
                .unit("kg")
                .quantityOnHand(new BigDecimal(quantity))
                .reorderThreshold(threshold == null ? null : new BigDecimal(threshold))
                .build();
        setId(item, UUID.randomUUID());
        return item;
    }

    private void setId(com.chefpay.core.domain.BaseEntity entity, UUID id) {
        entity.setId(id);
    }
}
