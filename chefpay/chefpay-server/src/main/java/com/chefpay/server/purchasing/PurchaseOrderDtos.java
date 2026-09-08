package com.chefpay.server.purchasing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class PurchaseOrderDtos {

    private PurchaseOrderDtos() {
    }

    public record PurchaseOrderItemDto(UUID id, UUID inventoryItemId, String inventoryItemName, String unit,
                                        BigDecimal orderedQuantity, BigDecimal unitPrice, BigDecimal lineTotal,
                                        BigDecimal receivedQuantity, BigDecimal acceptedQuantity,
                                        BigDecimal damagedQuantity, BigDecimal rejectedQuantity,
                                        BigDecimal remainingQuantity, String receivingNotes, long version) {
    }

    public record PurchaseOrderDto(UUID id, String poNumber, UUID branchId, String branchName,
                                    UUID supplierId, String supplierName, String status,
                                    String createdByName, String approvedByName, LocalDateTime approvedAt,
                                    String rejectedByName, LocalDateTime rejectedAt, String rejectionReason,
                                    LocalDateTime submittedAt, LocalDateTime closedAt, String notes,
                                    BigDecimal totalAmount, List<PurchaseOrderItemDto> items,
                                    LocalDateTime createdAt, long version,
                                    // Bistrodesk branch-isolation release (requirement #4 of the follow-up 10-item
                                    // list) - true when this PO's branch has a real WhatsApp Business API configured
                                    // (see Branch#whatsappProvider's javadoc). The client uses this to decide
                                    // upfront whether sharing via WhatsApp should actually send through the API
                                    // (no need to open the WhatsApp app) or fall back to today's wa.me deep link.
                                    boolean whatsappApiConfigured) {
    }

    public record CreatePurchaseOrderItemRequest(@NotNull UUID inventoryItemId, @NotNull BigDecimal orderedQuantity,
                                                  @NotNull BigDecimal unitPrice) {
    }

    public record CreatePurchaseOrderRequest(@NotNull UUID branchId, @NotNull UUID supplierId, String notes,
                                              @NotEmpty List<@Valid CreatePurchaseOrderItemRequest> items) {
    }

    /** Full item-list replace, same "this is what a DRAFT looks like now" semantics as re-saving a
     * form - simpler and safer than a line-level patch API while a PO is still being assembled. */
    public record UpdatePurchaseOrderRequest(UUID supplierId, String notes,
                                              @NotEmpty List<@Valid CreatePurchaseOrderItemRequest> items, long version) {
    }

    public record RejectPurchaseOrderRequest(String reason, long version) {
    }

    public record ShareRequest(@NotNull String method, String recipient, long version) {
    }

    public record ReceiveLineRequest(@NotNull UUID purchaseOrderItemId, @NotNull BigDecimal receivedQuantity,
                                      @NotNull BigDecimal acceptedQuantity, BigDecimal damagedQuantity,
                                      BigDecimal rejectedQuantity, String notes) {
    }

    public record ReceiveItemsRequest(@NotEmpty List<@Valid ReceiveLineRequest> lines, long version) {
    }

    public record ShareLogDto(UUID id, String method, String recipient, String sentByName, LocalDateTime sentAt, String status) {
    }

    public record ReplenishmentSuggestionDto(UUID inventoryItemId, String itemName, String unit,
                                              BigDecimal quantityOnHand, BigDecimal reorderThreshold,
                                              BigDecimal pendingOrderedQuantity, BigDecimal suggestedQuantity,
                                              String reason) {
    }

    public record ReplenishmentSuggestionsResponse(List<ReplenishmentSuggestionDto> suggestions, String aiNarrative) {
    }

    /** Round 14 (F2.2) - the confirmed-missing "convert a suggestion into a draft PO" action.
     * Deliberately reuses {@link CreatePurchaseOrderItemRequest}/{@code PurchaseOrderService
     * #createPurchaseOrder} verbatim rather than a new service method: a manager picks the branch
     * and supplier (neither is implied by a suggestion - {@link com.chefpay.core.domain.InventoryItem}
     * is not branch-scoped and has no supplier until {@link com.chefpay.core.domain.InventoryItem
     * #getPreferredSupplier()} is set), reviews/edits quantities and unit prices exactly as if
     * building the PO by hand, and this is purely a pre-fill convenience on top of that same
     * already-tested creation path. */
    public record CreateDraftPoFromSuggestionsRequest(@NotNull UUID branchId, @NotNull UUID supplierId, String notes,
                                                       @NotEmpty List<@Valid CreatePurchaseOrderItemRequest> items) {
    }

    /** Bistrodesk Phase 9 (requirement #19-22): a provider-agnostic shape for "a WhatsApp message
     * arrived from this phone number" - deliberately NOT modeled after one specific vendor's actual
     * webhook JSON (Meta Cloud API and Twilio each have their own, incompatible shape), matching
     * this requirement's own "explicitly not tightly coupled" instruction. A real integration's
     * webhook adapter would translate that vendor's payload into this shape before calling {@code
     * PurchaseOrderService#handleInboundSupplierReply} - see {@code SupplierReplyWebhookController}'s
     * javadoc for the rest of that story. */
    public record InboundSupplierReplyRequest(@NotNull String from, @NotNull String text) {
    }

    /** {@code matchedPoNumber} is null when no PO was ever sent a WhatsApp document from a number
     * matching {@code from} - still a 200 (a webhook provider should never be told to retry a
     * delivery that was received and understood, just not attributable to a known PO).
     * {@code ownerNotified} reflects whether a real email actually went out to {@code
     * Restaurant#getCriticalAlertRecipientEmails()} - false whenever that's unconfigured, which is
     * the honest, expected state until an operator sets it. */
    public record InboundSupplierReplyResult(String matchedPoNumber, boolean ownerNotified) {
    }
}
