package com.chefpay.core.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * F2.3 - "OCR-Assisted Supplier Invoice Intake." A staff member photographs/uploads a supplier
 * invoice; {@code OcrService}/{@code SupplierInvoiceService} extract candidate line items for a
 * manager to review, correct, match to {@link InventoryItem}s, and confirm - only {@link
 * #status} {@code CONFIRMED} ever updates ingredient landed cost or stock (see {@link
 * SupplierInvoiceStatus}'s javadoc). Confirmed invoices feed {@link InventoryItem#getCostPerUnit()},
 * which in turn feeds recipe costing (F2.1) and menu-engineering margins (F2.4) - exactly the data
 * flow the SRS describes.
 *
 * <p>{@link #rawImageBase64} follows the same convention {@link Restaurant#getLogoImageBase64()}
 * already established: plain Base64 TEXT rather than a BLOB column or a separate file-storage
 * service, since this app has no existing file-upload infrastructure. A single invoice photo is
 * larger than a logo but still small enough (a few hundred KB) that this remains the simplest
 * correct choice rather than standing up new infrastructure for one feature.
 */
@Entity
@Table(name = "supplier_invoice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class SupplierInvoice extends BaseEntity {

    @ManyToOne
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(columnDefinition = "TEXT")
    private String rawImageBase64;

    /** Raw text handed back by whichever extraction path ran (Tesseract OCR or the AI vision
     * assist) - kept for the manager's own reference/debugging on the review screen, never parsed
     * again after the initial draft lines are built. Null when extraction was skipped entirely
     * (see {@link #extractionMethod}). */
    @Column(columnDefinition = "TEXT")
    private String extractedRawText;

    /** {@code TESSERACT} | {@code AI_VISION} | {@code MANUAL} - which extraction path actually
     * produced (or failed to produce) {@link #extractedRawText}/the draft lines, shown on the
     * review screen so a manager knows how much to trust the draft before editing it. */
    @Column(length = 20)
    private String extractionMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SupplierInvoiceStatus status = SupplierInvoiceStatus.PENDING_REVIEW;

    @Column(length = 1000)
    private String notes;

    @ManyToOne
    @JoinColumn(name = "confirmed_by")
    private AppUser confirmedBy;

    private LocalDateTime confirmedAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt asc")
    @Builder.Default
    private List<SupplierInvoiceLine> lines = new ArrayList<>();
}
