package com.chefpay.core.repository;

import com.chefpay.core.domain.PurchaseOrderShareLog;
import com.chefpay.core.domain.PurchaseOrderShareMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PurchaseOrderShareLogRepository extends JpaRepository<PurchaseOrderShareLog, UUID> {

    List<PurchaseOrderShareLog> findByPurchaseOrderIdOrderBySentAtDesc(UUID purchaseOrderId);

    /** Bistrodesk Phase 9 (requirement #19-22): the candidate set an inbound supplier WhatsApp
     * reply is matched against - every PO a WhatsApp document was ever sent to, most recent first,
     * so {@code PurchaseOrderService#handleInboundSupplierReply} can find the most recent PO shared
     * with whatever phone number the reply came from (best-effort, since a phone number isn't
     * unique to one PO - a supplier gets sent several over time). */
    List<PurchaseOrderShareLog> findByMethodOrderBySentAtDesc(PurchaseOrderShareMethod method);
}
