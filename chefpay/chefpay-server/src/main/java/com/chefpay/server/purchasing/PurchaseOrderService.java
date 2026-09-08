package com.chefpay.server.purchasing;

import com.chefpay.core.domain.*;
import com.chefpay.core.repository.*;
import com.chefpay.core.service.AuditService;
import com.chefpay.core.service.NumberGeneratorService;
import com.chefpay.server.billing.EmailReceiptService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.inventory.InventoryService;
import com.chefpay.server.purchasing.whatsapp.WhatsAppService;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Full Purchase Order lifecycle - Round 12 §12-§26. See {@link PurchaseOrderStatus}'s javadoc for
 * the state machine and {@link SupplierChannel}'s javadoc for the offline-today/pluggable-later
 * sharing design. Every inventory quantity change this service ever makes goes through {@code
 * InventoryService#recordTransaction} (never a direct {@code InventoryItem} setter), the same
 * "always via a ledger transaction" discipline {@code InventoryService} itself already documents.
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    /** A PO may still be shared/re-shared with the supplier once approved and until fully received. */
    private static final Set<PurchaseOrderStatus> SHAREABLE_STATUSES =
            EnumSet.of(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.SENT_TO_SUPPLIER, PurchaseOrderStatus.PARTIALLY_RECEIVED);

    /** A PO may receive stock once approved and until every line is fully received. */
    private static final Set<PurchaseOrderStatus> RECEIVABLE_STATUSES =
            EnumSet.of(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.SENT_TO_SUPPLIER, PurchaseOrderStatus.PARTIALLY_RECEIVED);

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderShareLogRepository shareLogRepository;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final AppUserRepository appUserRepository;
    private final RestaurantRepository restaurantRepository;
    private final NumberGeneratorService numberGeneratorService;
    private final InventoryService inventoryService;
    private final EmailReceiptService emailReceiptService;
    private final SupplierChannel supplierChannel;
    private final WhatsAppService whatsAppService;
    private final AuditService auditService;
    private final WebSocketEventPublisher eventPublisher;

    // ---- CRUD ----

    @Transactional(readOnly = true)
    public List<PurchaseOrder> listPurchaseOrders(UUID branchId) {
        return branchId == null
                ? purchaseOrderRepository.findByOrderByCreatedAtDesc()
                : purchaseOrderRepository.findByBranchIdOrderByCreatedAtDesc(branchId);
    }

    @Transactional(readOnly = true)
    public PurchaseOrder getPurchaseOrder(UUID id) {
        return purchaseOrderRepository.findById(id).orElseThrow(() -> ApiException.notFound("Purchase order not found"));
    }

    @Transactional
    public PurchaseOrder createPurchaseOrder(UUID branchId, UUID supplierId, String notes,
                                              List<PurchaseOrderDtos.CreatePurchaseOrderItemRequest> itemInputs, UUID actorUserId) {
        Branch branch = branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        Supplier supplier = supplierRepository.findById(supplierId).orElseThrow(() -> ApiException.notFound("Supplier not found"));
        assertSupplierBranch(supplier, branch);
        AppUser creator = appUserRepository.findById(actorUserId).orElseThrow(() -> ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required to create a purchase order."));

        PurchaseOrder po = PurchaseOrder.builder()
                .poNumber(numberGeneratorService.next("PO"))
                .branch(branch)
                .supplier(supplier)
                .status(PurchaseOrderStatus.DRAFT)
                .createdBy(creator)
                .notes(notes)
                .build();
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        replaceItems(saved, itemInputs);

        auditService.record(actorUserId, null, "PurchaseOrder", saved.getId(), "PO_CREATED", null, saved.getPoNumber(), null, CorrelationIdHolder.get());
        publish(saved, "PO_CREATED");
        return saved;
    }

    @Transactional
    public PurchaseOrder updatePurchaseOrder(UUID id, UUID supplierId, String notes,
                                              List<PurchaseOrderDtos.CreatePurchaseOrderItemRequest> itemInputs, long expectedVersion, UUID actorUserId) {
        PurchaseOrder po = loadForUpdate(id, expectedVersion);
        if (po.getStatus() != PurchaseOrderStatus.DRAFT && po.getStatus() != PurchaseOrderStatus.PENDING_APPROVAL) {
            throw ApiException.conflict("PO_NOT_EDITABLE", "Purchase order " + po.getPoNumber()
                    + " can only be edited while Draft or Pending Approval (currently " + po.getStatus() + ").");
        }
        if (supplierId != null) {
            Supplier supplier = supplierRepository.findById(supplierId).orElseThrow(() -> ApiException.notFound("Supplier not found"));
            assertSupplierBranch(supplier, po.getBranch());
            po.setSupplier(supplier);
        }
        if (notes != null) {
            po.setNotes(notes);
        }
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        if (itemInputs != null) {
            replaceItems(saved, itemInputs);
        }
        auditService.record(actorUserId, null, "PurchaseOrder", saved.getId(), "PO_MODIFIED", null, null, null, CorrelationIdHolder.get());
        publish(saved, "PO_MODIFIED");
        return saved;
    }

    private void replaceItems(PurchaseOrder po, List<PurchaseOrderDtos.CreatePurchaseOrderItemRequest> itemInputs) {
        List<PurchaseOrderItem> existing = purchaseOrderItemRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(po.getId());
        if (!existing.isEmpty()) {
            purchaseOrderItemRepository.deleteAll(existing);
        }
        List<PurchaseOrderItem> fresh = new ArrayList<>();
        for (PurchaseOrderDtos.CreatePurchaseOrderItemRequest input : itemInputs) {
            InventoryItem inventoryItem = inventoryItemRepository.findById(input.inventoryItemId())
                    .orElseThrow(() -> ApiException.notFound("Inventory item not found"));
            if (input.orderedQuantity() == null || input.orderedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("INVALID_QUANTITY", "Ordered quantity must be greater than zero for " + inventoryItem.getName() + ".");
            }
            if (input.unitPrice() == null || input.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw ApiException.badRequest("INVALID_PRICE", "Unit price can't be negative for " + inventoryItem.getName() + ".");
            }
            fresh.add(PurchaseOrderItem.builder()
                    .purchaseOrder(po)
                    .inventoryItem(inventoryItem)
                    .orderedQuantity(input.orderedQuantity())
                    .unitPrice(input.unitPrice())
                    .build());
        }
        purchaseOrderItemRepository.saveAll(fresh);
    }

    // ---- Approval workflow ----

    /** Round 12 §13: submits a DRAFT for approval. Whether it lands directly in APPROVED or first
     * waits in PENDING_APPROVAL depends on {@code Restaurant#poApprovalRequired} (the master
     * on/off gate) and whether the submitter personally holds {@code PURCHASE_ORDER_APPROVE} - a
     * manager with that permission approves simply by submitting, exactly like {@code
     * BillingService#applyDiscount} needs no separate approval step for someone who already holds
     * {@code DISCOUNT_APPROVE}. */
    @Transactional
    public PurchaseOrder submitForApproval(UUID id, long expectedVersion, UUID actorUserId) {
        PurchaseOrder po = loadForUpdate(id, expectedVersion);
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw ApiException.conflict("PO_NOT_DRAFT", "Purchase order " + po.getPoNumber() + " has already been submitted.");
        }
        if (po.getItems().isEmpty()) {
            throw ApiException.badRequest("PO_HAS_NO_ITEMS", "Add at least one item before submitting this purchase order.");
        }
        AppUser submitter = appUserRepository.findById(actorUserId).orElseThrow(() -> ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required."));
        Restaurant restaurant = currentRestaurant();
        po.setSubmittedAt(LocalDateTime.now());

        if (!restaurant.isPoApprovalRequired() || hasPermission(submitter, "PURCHASE_ORDER_APPROVE")) {
            po.setStatus(PurchaseOrderStatus.APPROVED);
            po.setApprovedBy(submitter);
            po.setApprovedAt(LocalDateTime.now());
            PurchaseOrder saved = purchaseOrderRepository.save(po);
            auditService.record(actorUserId, null, "PurchaseOrder", saved.getId(), "PO_APPROVED", "DRAFT", "APPROVED",
                    "Auto-approved on submission", CorrelationIdHolder.get());
            publish(saved, "PO_APPROVED");
            return saved;
        }

        po.setStatus(PurchaseOrderStatus.PENDING_APPROVAL);
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        auditService.record(actorUserId, null, "PurchaseOrder", saved.getId(), "PO_SUBMITTED", "DRAFT", "PENDING_APPROVAL", null, CorrelationIdHolder.get());
        publish(saved, "PO_SUBMITTED");
        return saved;
    }

    @Transactional
    public PurchaseOrder approve(UUID id, long expectedVersion, UUID actorUserId) {
        PurchaseOrder po = loadForUpdate(id, expectedVersion);
        if (po.getStatus() != PurchaseOrderStatus.PENDING_APPROVAL) {
            throw ApiException.conflict("PO_NOT_PENDING", "Purchase order " + po.getPoNumber()
                    + " is not awaiting approval (currently " + po.getStatus() + ").");
        }
        AppUser approver = appUserRepository.findById(actorUserId).orElseThrow(() -> ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required."));
        po.setStatus(PurchaseOrderStatus.APPROVED);
        po.setApprovedBy(approver);
        po.setApprovedAt(LocalDateTime.now());
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        auditService.record(actorUserId, null, "PurchaseOrder", saved.getId(), "PO_APPROVED", "PENDING_APPROVAL", "APPROVED", null, CorrelationIdHolder.get());
        publish(saved, "PO_APPROVED");
        return saved;
    }

    @Transactional
    public PurchaseOrder reject(UUID id, String reason, long expectedVersion, UUID actorUserId) {
        PurchaseOrder po = loadForUpdate(id, expectedVersion);
        if (po.getStatus() != PurchaseOrderStatus.PENDING_APPROVAL) {
            throw ApiException.conflict("PO_NOT_PENDING", "Purchase order " + po.getPoNumber()
                    + " is not awaiting approval (currently " + po.getStatus() + ").");
        }
        AppUser rejecter = appUserRepository.findById(actorUserId).orElseThrow(() -> ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required."));
        po.setStatus(PurchaseOrderStatus.REJECTED);
        po.setRejectedBy(rejecter);
        po.setRejectedAt(LocalDateTime.now());
        po.setRejectionReason(reason);
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        auditService.record(actorUserId, null, "PurchaseOrder", saved.getId(), "PO_REJECTED", "PENDING_APPROVAL", "REJECTED", reason, CorrelationIdHolder.get());
        publish(saved, "PO_REJECTED");
        return saved;
    }

    @Transactional
    public PurchaseOrder cancel(UUID id, long expectedVersion, UUID actorUserId) {
        PurchaseOrder po = loadForUpdate(id, expectedVersion);
        if (!po.getStatus().canTransitionTo(PurchaseOrderStatus.CANCELLED)) {
            throw ApiException.conflict("PO_NOT_CANCELLABLE", "Purchase order " + po.getPoNumber()
                    + " can no longer be cancelled (currently " + po.getStatus() + ").");
        }
        PurchaseOrderStatus from = po.getStatus();
        po.setStatus(PurchaseOrderStatus.CANCELLED);
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        auditService.record(actorUserId, null, "PurchaseOrder", saved.getId(), "PO_CANCELLED", from.name(), "CANCELLED", null, CorrelationIdHolder.get());
        publish(saved, "PO_CANCELLED");
        return saved;
    }

    @Transactional
    public PurchaseOrder close(UUID id, long expectedVersion, UUID actorUserId) {
        PurchaseOrder po = loadForUpdate(id, expectedVersion);
        if (po.getStatus() != PurchaseOrderStatus.RECEIVED) {
            throw ApiException.conflict("PO_NOT_RECEIVED", "Purchase order " + po.getPoNumber()
                    + " must be fully Received before it can be closed (currently " + po.getStatus() + ").");
        }
        po.setStatus(PurchaseOrderStatus.CLOSED);
        po.setClosedAt(LocalDateTime.now());
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        auditService.record(actorUserId, null, "PurchaseOrder", saved.getId(), "PO_CLOSED", "RECEIVED", "CLOSED", null, CorrelationIdHolder.get());
        publish(saved, "PO_CLOSED");
        return saved;
    }

    // ---- Sharing (§19-§20) ----

    /** Print is a client-side-only action (see {@link SupplierChannel}'s javadoc) - the client calls
     * this AFTER it already opened the print dialog, purely to record the audit entry (§20's
     * "sent-by/date/method/recipient/status"). Email actually sends here, via the same {@link
     * EmailReceiptService} a billing receipt email uses. Api goes through {@link SupplierChannel},
     * which today always reports "not configured" - see {@link UnavailableSupplierApiChannel}'s
     * javadoc for why that's an honest failure rather than a fake success.
     *
     * <p>WhatsApp (branch-isolation release, requirement #4) is a hybrid: when {@link
     * WhatsAppService#isConfigured} for this PO's branch, this method actually sends through the
     * configured Meta/Twilio/360dialog account - the client never needs to open the WhatsApp app at
     * all for that branch (see {@code PurchaseOrderDto#whatsappApiConfigured}, which lets the client
     * decide this upfront). A branch that hasn't bought/configured a WhatsApp Business API plan
     * falls back to exactly the pre-existing behavior: the client already opened the {@code wa.me}
     * deep link before calling this, and this call only records the audit entry - never blocking a
     * restaurant that hasn't set up real API credentials yet.
     *
     * <p>A successful share on a PO still in APPROVED advances it to SENT_TO_SUPPLIER; a re-send
     * once already SENT_TO_SUPPLIER/PARTIALLY_RECEIVED leaves status untouched. */
    @Transactional
    public PurchaseOrder shareWithSupplier(UUID id, String methodRaw, String recipient, long expectedVersion, UUID actorUserId) {
        PurchaseOrder po = loadForUpdate(id, expectedVersion);
        if (!SHAREABLE_STATUSES.contains(po.getStatus())) {
            throw ApiException.conflict("PO_NOT_SHAREABLE", "Purchase order " + po.getPoNumber()
                    + " must be Approved before it can be sent to the supplier (currently " + po.getStatus() + ").");
        }
        PurchaseOrderShareMethod method = parseShareMethod(methodRaw);
        AppUser sender = appUserRepository.findById(actorUserId).orElseThrow(() -> ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required."));
        String status;

        switch (method) {
            case EMAIL -> {
                String documentText = generateDocumentText(po);
                try {
                    emailReceiptService.sendDocument(recipient, "Purchase Order - " + po.getPoNumber(), documentText);
                    status = "SENT";
                } catch (ApiException ex) {
                    logShare(po, method, recipient, sender, "FAILED - " + ex.getMessage());
                    throw ex;
                }
            }
            case WHATSAPP -> {
                if (whatsAppService.isConfigured(po.getBranch())) {
                    String documentText = generateDocumentText(po);
                    SupplierChannel.Result result = whatsAppService.send(po.getBranch(), recipient, documentText);
                    if (!result.success()) {
                        logShare(po, method, recipient, sender, "FAILED - " + result.statusMessage());
                        throw ApiException.badRequest("WHATSAPP_API_UNAVAILABLE", result.statusMessage());
                    }
                    status = "SENT";
                } else {
                    // Not configured for this branch - the client already opened the wa.me deep link
                    // before calling this (see this method's javadoc), so there's nothing left to do
                    // but record it, exactly like PRINT below.
                    status = "SENT";
                }
            }
            case API -> {
                String documentText = generateDocumentText(po);
                SupplierChannel.Result result = supplierChannel.send(po, recipient, documentText);
                if (!result.success()) {
                    logShare(po, method, recipient, sender, "FAILED - " + result.statusMessage());
                    throw ApiException.badRequest("SUPPLIER_API_UNAVAILABLE", result.statusMessage());
                }
                status = "SENT";
            }
            // PRINT already happened client-side before this call - see this method's javadoc.
            default -> status = "SENT";
        }

        logShare(po, method, recipient, sender, status);
        if (po.getStatus() == PurchaseOrderStatus.APPROVED) {
            po.setStatus(PurchaseOrderStatus.SENT_TO_SUPPLIER);
            purchaseOrderRepository.save(po);
        }
        auditService.record(actorUserId, null, "PurchaseOrder", po.getId(), "PO_SHARED", null, method.name(), recipient, CorrelationIdHolder.get());
        publish(po, "PO_SHARED");
        return po;
    }

    /** Backs {@code PurchaseOrderDto#whatsappApiConfigured} - lets {@code PurchaseOrderController}
     * tell the client upfront whether sharing this PO via WhatsApp will actually send through a
     * real API (see {@link #shareWithSupplier}'s javadoc) or fall back to the {@code wa.me} deep
     * link, without duplicating {@link WhatsAppService#isConfigured}'s gate logic in the controller. */
    public boolean isWhatsAppConfigured(PurchaseOrder po) {
        return whatsAppService.isConfigured(po.getBranch());
    }

    private void logShare(PurchaseOrder po, PurchaseOrderShareMethod method, String recipient, AppUser sender, String status) {
        shareLogRepository.save(PurchaseOrderShareLog.builder()
                .purchaseOrder(po).method(method).recipient(recipient).sentBy(sender).sentAt(LocalDateTime.now()).status(status)
                .build());
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderShareLog> listShareLog(UUID poId) {
        return shareLogRepository.findByPurchaseOrderIdOrderBySentAtDesc(poId);
    }

    /** Round 12 §19: a professional, printable plain-text PO document - reused verbatim for the
     * Print action (client shows/prints this text, same {@code ReceiptPrinter} the billing
     * receipt already uses), the Email body, and the API channel's payload, so all three ways of
     * sharing a PO always say exactly the same thing. */
    @Transactional(readOnly = true)
    public String generateDocumentText(PurchaseOrder po) {
        Restaurant restaurant = currentRestaurant();
        String currency = restaurant.getCurrencySymbol();
        int width = 64;
        StringBuilder sb = new StringBuilder();
        sb.append(center(restaurant.getName(), width)).append('\n');
        if (restaurant.getGstin() != null) {
            sb.append("GSTIN: ").append(restaurant.getGstin()).append('\n');
        }
        sb.append(center("PURCHASE ORDER", width)).append('\n');
        sb.append("-".repeat(width)).append('\n');
        sb.append("PO Number: ").append(po.getPoNumber()).append('\n');
        sb.append("Date: ").append(po.getCreatedAt()).append('\n');
        sb.append("Branch: ").append(po.getBranch().getName()).append('\n');
        sb.append("Status: ").append(po.getStatus()).append('\n');
        sb.append("-".repeat(width)).append('\n');
        sb.append("Supplier: ").append(po.getSupplier().getName()).append('\n');
        if (po.getSupplier().getContactPerson() != null) sb.append("Contact: ").append(po.getSupplier().getContactPerson()).append('\n');
        if (po.getSupplier().getPhone() != null) sb.append("Phone: ").append(po.getSupplier().getPhone()).append('\n');
        if (po.getSupplier().getEmail() != null) sb.append("Email: ").append(po.getSupplier().getEmail()).append('\n');
        if (po.getSupplier().getAddress() != null) sb.append("Address: ").append(po.getSupplier().getAddress()).append('\n');
        sb.append("-".repeat(width)).append('\n');
        sb.append(String.format("%-28s %8s %10s %12s%n", "Item", "Qty", "Unit ₹", "Total"));
        sb.append("-".repeat(width)).append('\n');
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (PurchaseOrderItem item : po.getItems()) {
            String name = item.getInventoryItem().getName();
            String trimmedName = name.length() <= 28 ? name : name.substring(0, 27) + "…";
            sb.append(String.format("%-28s %8s %10s %12s%n", trimmedName,
                    item.getOrderedQuantity().stripTrailingZeros().toPlainString() + " " + item.getInventoryItem().getUnit(),
                    currency + item.getUnitPrice().setScale(2, RoundingMode.HALF_UP),
                    currency + item.lineTotal().setScale(2, RoundingMode.HALF_UP)));
            grandTotal = grandTotal.add(item.lineTotal());
        }
        sb.append("-".repeat(width)).append('\n');
        sb.append(String.format("%-49s %12s%n", "GRAND TOTAL", currency + grandTotal.setScale(2, RoundingMode.HALF_UP)));
        sb.append("-".repeat(width)).append('\n');
        if (po.getNotes() != null && !po.getNotes().isBlank()) {
            sb.append("Notes: ").append(po.getNotes()).append('\n');
        }
        sb.append("Please confirm receipt of this order.\n");
        return sb.toString();
    }

    // ---- Inbound supplier replies (§19-§22, Bistrodesk Phase 9) ----

    /** The "supplier replied" auto-ack + forward-to-owner half of the WhatsApp requirement -
     * called by {@code SupplierReplyWebhookController} once it's verified the request actually
     * came from a configured provider (this method itself trusts its caller completely, same as
     * every other service method here). "Auto-ack" is honestly scoped to what's actually possible
     * without a real WhatsApp Business API account: there is no outbound send capability here to
     * automatically reply to the supplier's own chat, so acking means "durably recorded, matched to
     * a PO if possible, and forwarded on" - not an automated WhatsApp reply back to them. Matching a
     * reply to a PO is best-effort (a phone number was possibly sent more than one PO over time) -
     * the most recently WhatsApp-shared PO for that number is assumed to be the one being replied
     * to, same "most recent wins" convention used for {@code Order.customer} phone lookups. */
    @Transactional
    public PurchaseOrderDtos.InboundSupplierReplyResult handleInboundSupplierReply(String from, String text) {
        String digits = normalizePhone(from);
        PurchaseOrder matched = digits.isBlank() ? null : shareLogRepository.findByMethodOrderBySentAtDesc(PurchaseOrderShareMethod.WHATSAPP)
                .stream()
                .filter(log -> log.getRecipient() != null && normalizePhone(log.getRecipient()).endsWith(lastDigits(digits)))
                .map(PurchaseOrderShareLog::getPurchaseOrder)
                .findFirst()
                .orElse(null);

        String note = matched == null
                ? "No matching purchase order found for " + from
                : "Matched to " + matched.getPoNumber();
        auditService.record(null, null, "PurchaseOrder", matched == null ? null : matched.getId(),
                "SUPPLIER_REPLY_RECEIVED", null, text, note, CorrelationIdHolder.get());

        boolean ownerNotified = forwardReplyToOwner(matched, from, text);
        return new PurchaseOrderDtos.InboundSupplierReplyResult(matched == null ? null : matched.getPoNumber(), ownerNotified);
    }

    /** Real, working today: emails everyone in {@code Restaurant#getCriticalAlertRecipientEmails()}
     * (the same "who gets urgent notifications" config {@code AlertDispatchService} already uses -
     * reused rather than adding a second, near-duplicate "owner contact" setting). A WhatsApp-to-
     * owner forward would be the more natural channel for this specific requirement, but that needs
     * the exact same missing real WhatsApp Business API send capability {@link SupplierChannel}'s
     * javadoc already documents as not-yet-integrated - so this honestly does the one thing that
     * actually works without one, rather than silently no-op-ing or faking a WhatsApp send. Returns
     * false (never throws) when unconfigured or every send failed - a delivery-log write must never
     * fail the webhook's own ack. */
    private boolean forwardReplyToOwner(PurchaseOrder matched, String from, String text) {
        Restaurant restaurant = currentRestaurant();
        String recipients = restaurant.getCriticalAlertRecipientEmails();
        if (recipients == null || recipients.isBlank()) {
            return false;
        }
        String subject = "Supplier WhatsApp reply" + (matched == null ? "" : " - PO " + matched.getPoNumber());
        String body = "A WhatsApp reply arrived from " + from
                + (matched == null
                        ? " - no matching purchase order was found for this number."
                        : " regarding purchase order " + matched.getPoNumber() + " (" + matched.getSupplier().getName() + ").")
                + "\n\n" + text;
        boolean anySent = false;
        for (String address : recipients.split(",")) {
            String trimmed = address.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                emailReceiptService.sendDocument(trimmed, subject, body);
                anySent = true;
            } catch (Exception ex) {
                // Best-effort forward, same discipline AlertDispatchService's own email dispatch
                // documents - one recipient failing must never block the others or the ack itself.
            }
        }
        return anySent;
    }

    private String normalizePhone(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
    }

    /** The last 10 digits of a normalized phone number - enough to match "+91 98765 43210" against
     * "919876543210" or "09876543210" regardless of which country-code/leading-zero convention
     * either side happened to store, without needing real phone-number-library parsing for what's
     * still just a best-effort match. */
    private String lastDigits(String digits) {
        return digits.length() <= 10 ? digits : digits.substring(digits.length() - 10);
    }

    // ---- Receiving (§21-§24) ----

    /** Every accepted quantity here is posted through {@code InventoryService#recordTransaction}
     * (type {@code RECEIVE}) - never a direct {@code InventoryItem} mutation, matching that
     * service's own "always via a ledger transaction" rule. Damaged/rejected quantities are
     * recorded on the {@link PurchaseOrderItem} for the audit trail but never touch stock - they
     * were never usable. Each line's {@code receivedQuantity/acceptedQuantity/damagedQuantity/
     * rejectedQuantity} in the request is a DELTA for this receiving event, not a running total
     * (§22 partial receiving may be called several times against the same PO as deliveries arrive
     * in installments). */
    @Transactional
    public PurchaseOrder receiveItems(UUID id, List<PurchaseOrderDtos.ReceiveLineRequest> lines, long expectedVersion, UUID actorUserId) {
        PurchaseOrder po = loadForUpdate(id, expectedVersion);
        if (!RECEIVABLE_STATUSES.contains(po.getStatus())) {
            throw ApiException.conflict("PO_NOT_RECEIVABLE", "Purchase order " + po.getPoNumber()
                    + " must be Approved/Sent/Partially Received to receive against it (currently " + po.getStatus() + ").");
        }
        AppUser receiver = appUserRepository.findById(actorUserId).orElseThrow(() -> ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required."));

        for (PurchaseOrderDtos.ReceiveLineRequest line : lines) {
            PurchaseOrderItem item = po.getItems().stream().filter(i -> i.getId().equals(line.purchaseOrderItemId()))
                    .findFirst().orElseThrow(() -> ApiException.notFound("Purchase order item not found on this order"));

            BigDecimal received = line.receivedQuantity() == null ? BigDecimal.ZERO : line.receivedQuantity();
            BigDecimal accepted = line.acceptedQuantity() == null ? BigDecimal.ZERO : line.acceptedQuantity();
            BigDecimal damaged = line.damagedQuantity() == null ? BigDecimal.ZERO : line.damagedQuantity();
            BigDecimal rejected = line.rejectedQuantity() == null ? BigDecimal.ZERO : line.rejectedQuantity();

            if (received.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // nothing arrived for this line in this receiving event - skip, not an error
            }
            if (accepted.add(damaged).add(rejected).compareTo(received) != 0) {
                throw ApiException.badRequest("RECEIVING_MISMATCH", "For " + item.getInventoryItem().getName()
                        + ", accepted + damaged + rejected must add up to the received quantity.");
            }
            if (item.getReceivedQuantity().add(received).compareTo(item.getOrderedQuantity()) > 0) {
                throw ApiException.badRequest("RECEIVING_EXCEEDS_ORDER", "Receiving " + received + " " + item.getInventoryItem().getUnit()
                        + " of " + item.getInventoryItem().getName() + " would exceed the " + item.getOrderedQuantity() + " ordered - "
                        + "start a new purchase order for any genuine over-delivery.");
            }

            item.setReceivedQuantity(item.getReceivedQuantity().add(received));
            item.setAcceptedQuantity(item.getAcceptedQuantity().add(accepted));
            item.setDamagedQuantity(item.getDamagedQuantity().add(damaged));
            item.setRejectedQuantity(item.getRejectedQuantity().add(rejected));
            if (line.notes() != null && !line.notes().isBlank()) {
                item.setReceivingNotes(item.getReceivingNotes() == null ? line.notes() : item.getReceivingNotes() + " | " + line.notes());
            }
            purchaseOrderItemRepository.save(item);

            if (accepted.compareTo(BigDecimal.ZERO) > 0) {
                String reason = "PO " + po.getPoNumber() + " receiving" + (line.notes() == null || line.notes().isBlank() ? "" : " - " + line.notes());
                inventoryService.recordTransaction(item.getInventoryItem().getId(), "RECEIVE", accepted, reason,
                        item.getInventoryItem().getVersion(), actorUserId);
            }

            auditService.record(actorUserId, null, "PurchaseOrderItem", item.getId(), "PO_ITEM_RECEIVED",
                    null, "received " + received + ", accepted " + accepted + ", damaged " + damaged + ", rejected " + rejected,
                    line.notes(), CorrelationIdHolder.get());
        }

        boolean fullyReceived = po.getItems().stream().allMatch(i -> i.remainingQuantity().compareTo(BigDecimal.ZERO) == 0);
        PurchaseOrderStatus newStatus = fullyReceived ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED;
        PurchaseOrderStatus from = po.getStatus();
        po.setStatus(newStatus);
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        auditService.record(actorUserId, null, "PurchaseOrder", saved.getId(), "PO_RECEIVED", from.name(), newStatus.name(), null, CorrelationIdHolder.get());
        publish(saved, "PO_RECEIVED");
        return saved;
    }

    // ---- helpers ----

    private boolean hasPermission(AppUser user, String permissionCode) {
        return user.getRole() != null && user.getRole().getPermissions().stream().anyMatch(p -> p.getCode().equals(permissionCode));
    }

    private PurchaseOrder loadForUpdate(UUID id, long expectedVersion) {
        PurchaseOrder po = getPurchaseOrder(id);
        if (po.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(PurchaseOrder.class, id);
        }
        return po;
    }

    /** Bistrodesk branch-isolation release (requirement #3/#6): a supplier now belongs to exactly
     * one branch (see {@link Supplier#getBranch()}'s javadoc) - {@code PurchaseOrderController}
     * already access-checks the caller against the PO's own branch, but that alone doesn't stop a
     * caller who legitimately works Branch A from attaching a Branch B supplier's id to a Branch A
     * PO by simply passing a different {@code supplierId} in the request body. This is the
     * server-side guard the "no ID/param manipulation should ever leak cross-branch data" rule
     * requires - a mismatch is a 400, not merely hidden client-side by the (now branch-filtered)
     * supplier picker. A null supplier branch (pre-backfill legacy row) is tolerated, never blocked. */
    private void assertSupplierBranch(Supplier supplier, Branch poBranch) {
        Branch supplierBranch = supplier.getBranch();
        if (supplierBranch != null && !supplierBranch.getId().equals(poBranch.getId())) {
            throw ApiException.badRequest("SUPPLIER_BRANCH_MISMATCH",
                    "Supplier " + supplier.getName() + " does not belong to this purchase order's branch.");
        }
    }

    private Restaurant currentRestaurant() {
        return restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
    }

    private void publish(PurchaseOrder po, String eventType) {
        eventPublisher.publish("/topic/purchase-orders", eventType, po.getId(), po.getVersion(), Map.of("poNumber", po.getPoNumber()));
    }

    private PurchaseOrderShareMethod parseShareMethod(String raw) {
        try {
            return PurchaseOrderShareMethod.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw ApiException.badRequest("INVALID_SHARE_METHOD", "Unknown share method: " + raw);
        }
    }

    /** Centers a heading within a fixed-width plain-text line, mirroring {@code
     * BillingService#center}'s identical helper for receipt text - this class generates its own
     * printable PO document (see {@link #generateDocumentText}) and has no dependency on
     * BillingService, so it needs its own copy rather than reaching across modules for one helper. */
    private String center(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat((width - text.length()) / 2) + text;
    }
}
