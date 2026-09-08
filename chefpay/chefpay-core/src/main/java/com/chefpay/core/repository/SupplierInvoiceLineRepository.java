package com.chefpay.core.repository;

import com.chefpay.core.domain.SupplierInvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupplierInvoiceLineRepository extends JpaRepository<SupplierInvoiceLine, UUID> {

    List<SupplierInvoiceLine> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);
}
