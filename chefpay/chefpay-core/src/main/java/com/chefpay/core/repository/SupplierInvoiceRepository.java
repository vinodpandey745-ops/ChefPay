package com.chefpay.core.repository;

import com.chefpay.core.domain.SupplierInvoice;
import com.chefpay.core.domain.SupplierInvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, UUID> {

    List<SupplierInvoice> findByOrderByCreatedAtDesc();

    List<SupplierInvoice> findByStatusOrderByCreatedAtDesc(SupplierInvoiceStatus status);
}
