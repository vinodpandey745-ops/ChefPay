package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A named printer configuration - Round 8. Replaces {@link Restaurant#getReceiptPrinterName()}'s
 * single global printer name with a purpose-tagged list: {@code printerName} matches what the
 * javafx module's {@code Printer.getAllPrinters()} (used by {@code ReceiptPrinter}) would return
 * for an OS print-queue name, while {@code name} is just a friendly label for staff (e.g. "Bills",
 * "KOT Printer"). {@code forBill}/{@code forKot}/{@code forEbill} are three independent flags
 * rather than one enum since a single physical printer can be assigned to more than one purpose at
 * once (e.g. both Bill and KOT) - the same "one printer, multiple purposes" shape a commercial
 * POS's Printer Listing screen shows.
 */
@Entity
@Table(name = "printer_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class PrinterProfile extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /** Friendly label shown to staff, e.g. "Bills", "KOT Printer". */
    @Column(nullable = false)
    private String name;

    /** OS print-queue name, e.g. as returned by {@code Printer.getAllPrinters()}. */
    @Column(nullable = false)
    private String printerName;

    @Builder.Default
    @Column(nullable = false)
    private boolean forBill = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean forKot = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean forEbill = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
