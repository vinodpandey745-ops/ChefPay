package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * One extracted/edited line of a {@link SupplierInvoice} (F2.3). {@link #inventoryItem} is
 * nullable until a manager matches this line to a real stock item on the review screen - a line
 * with no match, or no {@link #quantity}/{@link #unitCost}, is simply skipped by {@code
 * SupplierInvoiceService#confirm} rather than guessed at. Follows {@code RecipeService.saveRecipe}'s
 * "replace-all" convention: confirming an invoice resaves its whole line list fresh rather than
 * diffing individual edits.
 */
@Entity
@Table(name = "supplier_invoice_line")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class SupplierInvoiceLine extends BaseEntity {

    /** Bistrodesk fix (production {@code StackOverflowError} class - see {@code
     * Device#lastUser}'s javadoc for the full explanation): {@link SupplierInvoice#lines} is the
     * inverse side of this relationship - without this exclusion, hashing this line would walk
     * back into its parent invoice's line list and re-hash every sibling line forever. */
    @EqualsAndHashCode.Exclude
    @ManyToOne(optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private SupplierInvoice invoice;

    @ManyToOne
    @JoinColumn(name = "inventory_item_id")
    private InventoryItem inventoryItem;

    @Column(length = 300)
    private String description;

    @Column(precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(precision = 12, scale = 2)
    private BigDecimal unitCost;

    /** The line as originally read from OCR/AI extraction, before any manager edit - kept purely
     * for reference on the review screen. */
    @Column(length = 500)
    private String rawText;
}
