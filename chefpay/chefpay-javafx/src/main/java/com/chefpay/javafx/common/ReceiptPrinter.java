package com.chefpay.javafx.common;

import com.chefpay.javafx.client.dto.OrderDtos;
import javafx.event.ActionEvent;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.text.Text;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Shared plain-text receipt viewer/printer, used by every screen that can show a bill
 * ({@code BillingView}'s "View Receipt", {@code TableMatrixView}'s per-table print shortcut).
 * "Print" here opens the OS print dialog via JavaFX's {@link PrinterJob} for whatever printer -
 * including a receipt/thermal printer registered with the OS - the user has configured; there is
 * no ESC/POS or other printer-specific driver integration for the interactive path (that's the
 * still-not-started "Printer abstraction" line item in ARCHITECTURE.md §13's Phase 6). This is
 * exactly what happens if you select the receipt text and hit Ctrl+P.
 *
 * <p>Round 6 added two things that genuinely need to skip that dialog - an auto-printed
 * online-order KOT and a cash-drawer kick - see {@link #printSilently} and
 * {@link #openCashDrawer}'s javadocs for how (and how reliably) each actually works.
 */
public final class ReceiptPrinter {

    private ReceiptPrinter() {
    }

    public static void show(String title, String text) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setStyle("-fx-font-family: monospace;");
        area.setPrefSize(360, 480);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.getDialogPane().setContent(area);

        ButtonType printType = new ButtonType("Print", ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes().setAll(printType, ButtonType.CLOSE);

        // An Alert closes on any button press by default - intercept Print so the dialog stays
        // open afterward (the user may want to review the text or print again before dismissing).
        Button printButton = (Button) alert.getDialogPane().lookupButton(printType);
        printButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            print(text);
        });

        alert.showAndWait();
    }

    private static void print(String text) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            new Alert(Alert.AlertType.WARNING, "No printer is configured on this computer.").showAndWait();
            return;
        }
        if (job.showPrintDialog(null)) {
            // Round 11: try sending the exact same bytes straight to whichever printer the user
            // just picked, via printRaw - see its javadoc for why that (not the Text-node rendering
            // below) is what actually keeps a receipt/KOT's fixed-width columns aligned on paper.
            String printerName = job.getPrinter() == null ? null : job.getPrinter().getName();
            if (printRaw(printerName, text)) {
                // The raw bytes above already went straight to the OS print queue - this JavaFX job
                // only existed to drive the "pick a printer" dialog, so cancel it rather than
                // leaving an unsubmitted job dangling.
                job.cancelJob();
                return;
            }
            // Fallback for the rare printer queue that rejects a raw BYTE_ARRAY job outright (rather
            // than just mis-rendering it) - renders through PrinterJob so the print still happens,
            // with the same font-substitution/margin alignment caveat printSilently's javadoc
            // documents for its equivalent fallback.
            Text node = new Text(text);
            node.setStyle("-fx-font-family: monospace; -fx-font-size: 10px;");
            if (job.printPage(node)) {
                job.endJob();
            } else {
                new Alert(Alert.AlertType.ERROR, "Printing failed.").showAndWait();
            }
        }
    }

    /** The OS print-queue names available to pick from in Settings' Receipt Printer field -
     * whatever {@link Printer#getAllPrinters()} reports, which is exactly the same list the manual
     * "Print" dialog above already lets a user choose from. */
    public static List<String> availablePrinterNames() {
        return Printer.getAllPrinters().stream().map(Printer::getName).toList();
    }

    /** Prints straight to the named OS printer with no dialog - used for the two places a popup
     * would be actively wrong: an auto-printed online-order KOT (nobody's necessarily watching the
     * screen to click through a dialog) and, in principle, any other unattended print. Matches
     * {@code printerName} case-insensitively against {@link Printer#getAllPrinters()} by name
     * (exact match preferred, falls back to a "contains" match since OS printer names sometimes
     * carry a driver suffix Settings' dropdown might not show verbatim). Returns {@code false}
     * (never throws) when {@code printerName} is null/blank, no matching printer is found, or the
     * print itself fails - callers should treat that as "couldn't auto-print" and fall back to
     * {@link #show} so the ticket is never silently lost. */
    public static boolean printSilently(String printerName, String text) {
        if (printerName == null || printerName.isBlank()) {
            return false;
        }
        // Round 11: try the raw byte-stream path first - see printRaw's javadoc for why this (not
        // the PrinterJob/Text-node rendering below) is what actually keeps a receipt/KOT's
        // fixed-width columns aligned on paper. This is the fix for the reported "characters not
        // aligned on the paper" bug against every silent print in this app (auto-printed online-
        // order KOTs, the cash-drawer kick's sibling feature, and Round 11's new auto-printed
        // receipt-on-payment).
        if (printRaw(printerName, text)) {
            return true;
        }
        // Fallback for the rare printer queue that rejects a raw BYTE_ARRAY job outright (rather
        // than just mis-rendering it) - at least gets the ticket printed rather than silently
        // dropping it, with the original font-substitution/margin alignment risk this whole method
        // used to always carry.
        Printer printer = findPrinter(printerName);
        if (printer == null) {
            return false;
        }
        PrinterJob job = PrinterJob.createPrinterJob(printer);
        if (job == null) {
            return false;
        }
        job.setPrinter(printer);
        Text node = new Text(text);
        node.setStyle("-fx-font-family: monospace; -fx-font-size: 10px;");
        boolean ok = job.printPage(node);
        if (ok) {
            job.endJob();
        }
        return ok;
    }

    /** Sends {@code text} to the OS print queue as raw bytes (UTF-8) instead of rendering it as a
     * JavaFX {@link Text} node through {@link PrinterJob}. This is the actual fix for reported
     * "characters not aligned on the paper": {@code BillingService#generateReceiptText} and
     * {@link #buildKotText} both build the receipt/KOT as plain fixed-width text, padding columns
     * with spaces on the assumption every character in a row is the same width. Rendering that text
     * through {@code PrinterJob#printPage} depends on whatever font this OS substitutes for
     * {@code "-fx-font-family: monospace"} (not guaranteed to be truly fixed-width) and on the OS's
     * default page margins - either one silently breaks the column alignment the formatter worked
     * to produce. Sending the exact same bytes straight to the print queue instead means the
     * printer's own built-in fixed-width font (every real ESC/POS thermal printer has one baked
     * into its firmware) does the alignment, with no OS font substitution in the way - the same
     * "bypass PrinterJob entirely, talk to the print queue directly" trick {@link #openCashDrawer}
     * already used for its raw kick-drawer pulse.
     *
     * <p>Ends with a form-feed ({@code 0x0C}) so the printer advances/cuts the page the same way a
     * normal print job does. Uses UTF-8, which covers the default {@code currencySymbol} ("₹") on
     * most modern ESC/POS firmwares; if a specific printer's firmware doesn't render that
     * correctly in raw text mode, switching {@code currencySymbol} to "Rs." under Settings is the
     * practical workaround (no code change needed).
     *
     * @return true if the raw bytes were accepted by the print queue (same "accepted, not confirmed
     *         complete" caveat as {@link #openCashDrawer}) */
    public static boolean printRaw(String printerName, String text) {
        if (printerName == null || printerName.isBlank()) {
            return false;
        }
        PrintService service = findPrintService(printerName);
        if (service == null) {
            return false;
        }
        byte[] bytes = (text + "\f").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DocFlavor flavor = DocFlavor.BYTE_ARRAY.AUTOSENSE;
        Doc doc = new SimpleDoc(bytes, flavor, null);
        try {
            DocPrintJob job = service.createPrintJob();
            job.print(doc, new HashPrintRequestAttributeSet());
            return true;
        } catch (PrintException ex) {
            return false;
        }
    }

    private static Printer findPrinter(String printerName) {
        String needle = printerName.trim().toLowerCase();
        Printer contains = null;
        for (Printer candidate : Printer.getAllPrinters()) {
            String name = candidate.getName();
            if (name.equalsIgnoreCase(printerName.trim())) {
                return candidate;
            }
            if (contains == null && name.toLowerCase().contains(needle)) {
                contains = candidate;
            }
        }
        return contains;
    }

    /** Sends the standard ESC/POS "kick cash drawer" pulse ({@code ESC p 0 25 250}, the same
     * command basically every thermal receipt printer with a drawer kick-out port understands) to
     * the named OS print queue as a raw byte stream, bypassing {@link PrinterJob} entirely (that
     * API only knows how to render pages, not send raw device control bytes). This ONLY does
     * anything useful when: (a) {@code printerName} matches a real, currently-available
     * {@code javax.print.PrintService}, AND (b) that printer is an ESC/POS-compatible thermal
     * printer with a drawer physically wired to its kick-out port (the standard receipt-printer +
     * cash-drawer setup). Against a generic inkjet/laser printer this will either be silently
     * ignored by the driver or print a page of garbage characters - there is no reliable way to
     * detect "is this actually a drawer-capable thermal printer" from Java, which is exactly why
     * {@code Restaurant.cashDrawerEnabled} is an explicit opt-in toggle rather than always-on.
     *
     * @return true if the raw bytes were accepted by the print queue (NOT proof the drawer
     *         physically opened - this API has no way to confirm that) */
    public static boolean openCashDrawer(String printerName) {
        if (printerName == null || printerName.isBlank()) {
            return false;
        }
        PrintService service = findPrintService(printerName);
        if (service == null) {
            return false;
        }
        byte[] kick = {0x1B, 'p', 0x00, 0x19, (byte) 0xFA};
        DocFlavor flavor = DocFlavor.BYTE_ARRAY.AUTOSENSE;
        Doc doc = new SimpleDoc(kick, flavor, null);
        try {
            DocPrintJob job = service.createPrintJob();
            job.print(doc, new HashPrintRequestAttributeSet());
            return true;
        } catch (PrintException ex) {
            return false;
        }
    }

    private static PrintService findPrintService(String printerName) {
        String needle = printerName.trim().toLowerCase();
        PrintService contains = null;
        for (PrintService candidate : PrintServiceLookup.lookupPrintServices(null, null)) {
            String name = candidate.getName();
            if (name.equalsIgnoreCase(printerName.trim())) {
                return candidate;
            }
            if (contains == null && name.toLowerCase().contains(needle)) {
                contains = candidate;
            }
        }
        return contains;
    }

    /** Plain-text kitchen order ticket (KOT) - item list only, no prices, for the kitchen rather
     * than the guest. Deliberately a separate formatter from {@code BillingService
     * #generateReceiptText} (a customer-facing money receipt built server-side) rather than a
     * shared one - a KOT and a bill answer different questions and have always been different
     * documents on every real POS. Built client-side (not a server endpoint) since it's pure
     * presentation over data {@code OrderDto} already carries, same "no server round trip for
     * something the client already has" reasoning used elsewhere in this app. */
    public static String buildKotText(OrderDtos.OrderDto order, int width) {
        int w = width > 0 ? width : 40;
        StringBuilder sb = new StringBuilder();
        sb.append(center("KITCHEN ORDER TICKET", w)).append('\n');
        sb.append("-".repeat(w)).append('\n');
        sb.append("Order: ").append(order.orderNumber()).append('\n');
        sb.append("Type: ").append(order.orderType() == null ? "-" : order.orderType().replace('_', ' ')).append('\n');
        if (order.tableName() != null) {
            sb.append("Table: ").append(order.tableName()).append('\n');
        }
        if (order.customerName() != null) {
            sb.append("Customer: ").append(order.customerName())
                    .append(order.customerPhone() != null ? " (" + order.customerPhone() + ")" : "").append('\n');
        }
        if (order.createdAt() != null) {
            sb.append("Time: ").append(order.createdAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm a"))).append('\n');
        }
        sb.append("-".repeat(w)).append('\n');
        for (OrderDtos.OrderItemDto item : order.items()) {
            if ("CANCELLED".equals(item.status()) || "VOIDED".equals(item.status())) {
                continue;
            }
            sb.append(item.quantity().stripTrailingZeros().toPlainString()).append(" x ").append(item.menuItemName());
            if (item.priority()) {
                sb.append("  [PRIORITY]");
            }
            sb.append('\n');
            if (item.specialInstructions() != null && !item.specialInstructions().isBlank()) {
                sb.append("   * ").append(item.specialInstructions()).append('\n');
            }
        }
        sb.append("-".repeat(w)).append('\n');
        if (order.notes() != null && !order.notes().isBlank()) {
            sb.append("Notes: ").append(order.notes()).append('\n');
            sb.append("-".repeat(w)).append('\n');
        }
        return sb.toString();
    }

    private static String center(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat((width - text.length()) / 2) + text;
    }
}
