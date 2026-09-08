package com.chefpay.server.eod;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/**
 * Renders a {@link ZReportData} as a PDF (AI Backbone Addendum F1.1 technical note: "PDF
 * generation via a Java library (e.g. OpenPDF/iText)"). Generated synchronously within {@code
 * EodService#finalize}'s transaction - see {@code EodSession#zReportPdfBase64}'s javadoc for why
 * this round doesn't follow the doc's "may complete asynchronously" allowance.
 */
@Component
public class ZReportPdfBuilder {

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 18, Font.BOLD);
    private static final Font HEADING_FONT = new Font(Font.HELVETICA, 12, Font.BOLD);
    private static final Font NORMAL_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font BOLD_FONT = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font SMALL_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL);

    public byte[] build(ZReportData data) {
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(new Paragraph(data.restaurantName(), TITLE_FONT));
            document.add(new Paragraph("End-of-Day Report - "
                    + data.businessDate().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")), NORMAL_FONT));
            if (data.finalizedWithOverride()) {
                Paragraph override = new Paragraph(
                        "FINALIZED WITH LEVEL-3 OVERRIDE: " + nullToDash(data.finalizeOverrideReason()), BOLD_FONT);
                document.add(override);
            }
            document.add(spacer());

            document.add(new Paragraph("Financial Summary", HEADING_FONT));
            document.add(twoColumnTable(new String[][]{
                    {"Gross Sales", money(data.grossSales())},
                    {"Discounts", "-" + money(data.discountTotal())},
                    {"Tax", money(data.taxTotal())},
                    {"Service Charge", money(data.serviceChargeTotal())},
                    {"Tips", money(data.tipTotal())},
                    {"Net Total", money(data.netTotal())},
                    {"Orders Billed", String.valueOf(data.ordersBilled())},
            }));
            document.add(spacer());

            document.add(new Paragraph("Tender Breakdown", HEADING_FONT));
            PdfPTable tenderTable = new PdfPTable(3);
            tenderTable.setWidthPercentage(100);
            addHeaderCell(tenderTable, "Method");
            addHeaderCell(tenderTable, "Count");
            addHeaderCell(tenderTable, "Amount");
            for (ZReportData.TenderLine line : data.tenderBreakdown()) {
                addCell(tenderTable, line.method(), NORMAL_FONT, Element.ALIGN_LEFT);
                addCell(tenderTable, String.valueOf(line.count()), NORMAL_FONT, Element.ALIGN_RIGHT);
                addCell(tenderTable, money(line.amount()), NORMAL_FONT, Element.ALIGN_RIGHT);
            }
            document.add(tenderTable);
            document.add(spacer());

            document.add(new Paragraph("Cash Drawer Reconciliation", HEADING_FONT));
            document.add(twoColumnTable(new String[][]{
                    {"Opening Float", money(data.openingFloat())},
                    {"Expected Cash", money(data.expectedCash())},
                    {"Physical Count", money(data.physicalCash())},
                    {"Variance", money(data.cashVariance())},
                    {"Status", nullToDash(data.cashCountStatus())},
            }));
            document.add(spacer());

            document.add(new Paragraph("Fraud & Audit Review Summary", HEADING_FONT));
            document.add(new Paragraph(data.anomaliesTotal() + " anomal" + (data.anomaliesTotal() == 1 ? "y" : "ies")
                    + " flagged (" + data.anomaliesHighCritical() + " High/Critical), "
                    + data.anomaliesUnresolved() + " left unresolved at finalize.", NORMAL_FONT));
            for (String line : data.anomalySummaryLines()) {
                document.add(new Paragraph("• " + line, SMALL_FONT));
            }

            if (!data.lowStockSummaryLines().isEmpty()) {
                document.add(spacer());
                document.add(new Paragraph("Low Stock (Round 14 F2.2)", HEADING_FONT));
                for (String line : data.lowStockSummaryLines()) {
                    document.add(new Paragraph("• " + line, SMALL_FONT));
                }
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render Z-Report PDF", e);
        }
    }

    private PdfPTable twoColumnTable(String[][] rows) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[]{60f, 40f});
        } catch (com.lowagie.text.DocumentException ignored) {
            // setWidths only throws if the array length doesn't match column count - it always
            // does here (constant 2-element arrays), so this can never actually happen.
        }
        for (String[] row : rows) {
            addCell(table, row[0], NORMAL_FONT, Element.ALIGN_LEFT);
            addCell(table, row[1], BOLD_FONT, Element.ALIGN_RIGHT);
        }
        return table;
    }

    private void addHeaderCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BOLD_FONT));
        cell.setPadding(4);
        table.addCell(cell);
    }

    private void addCell(PdfPTable table, String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(4);
        cell.setHorizontalAlignment(alignment);
        table.addCell(cell);
    }

    private Paragraph spacer() {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(4);
        return p;
    }

    private String money(java.math.BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
