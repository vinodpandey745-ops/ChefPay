package com.chefpay.server.invoices;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.InventoryItem;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.domain.Supplier;
import com.chefpay.core.domain.SupplierInvoice;
import com.chefpay.core.domain.SupplierInvoiceLine;
import com.chefpay.core.domain.SupplierInvoiceStatus;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.InventoryItemRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.core.repository.SupplierInvoiceLineRepository;
import com.chefpay.core.repository.SupplierInvoiceRepository;
import com.chefpay.core.repository.SupplierRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.ai.AiProvider;
import com.chefpay.server.ai.AiService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.inventory.InventoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F2.3 - "OCR-Assisted Supplier Invoice Intake." {@link #scan} runs whichever extraction path is
 * available (see {@link OcrService} and the AI-vision fallback below) to produce a PENDING_REVIEW
 * draft; nothing is ever applied to inventory until {@link #confirm} is explicitly called by a
 * manager, matching F2.3's "OCR output is never auto-applied without review."
 *
 * <p>Extraction order: if {@code Restaurant#isOcrUseAiVisionAssist()} is on and AI is configured,
 * the existing multi-provider AI vision pipeline ({@link AiService#chatWithImage}, already proven
 * out by the AI Menu Import feature) runs first, since a vision-capable LLM can read tabular
 * invoice structure far more reliably than raw OCR text ever can - this is the SRS's own "a cloud
 * OCR API can be offered as a higher-accuracy opt-in" half of F2.3. Otherwise (the default),
 * Tesseract OCR runs first, matching the SRS's primary/offline-friendly recommendation; if
 * Tesseract is unavailable or fails and AI happens to be configured, AI vision is still tried as a
 * fallback (some real extraction beats none, regardless of the preference flag). If neither
 * produces anything, the invoice is still created with zero draft lines and a clear "enter items
 * manually" note (NFR-9 graceful degradation) - a manager can always type/match every line by hand
 * regardless of which extraction path ran.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierInvoiceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String AI_SYSTEM_PROMPT = """
            You read photos of restaurant supplier invoices or delivery notes. Extract every line item
            you can clearly identify into strict JSON. Respond with ONLY a JSON array (no prose, no
            markdown fences) where each element has exactly these fields:
            {"description": string, "quantity": number|null, "unitCost": number|null}
            "quantity" and "unitCost" are numeric only (no currency symbols or unit names in the number
            itself) - use null for either field if you genuinely cannot read it on that line. If you
            cannot read any line items at all, respond with an empty JSON array: []
            """;

    /** Best-effort heuristic for a raw Tesseract text line: "<description> <qty> <unitCost>
     * [<total>]" is a common simple invoice layout, but far from universal - this is deliberately
     * humble (only fires when exactly 2 or 3 numeric tokens are found) rather than guessing on
     * ambiguous lines; every line is still fully editable by the manager regardless. */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    private final SupplierInvoiceRepository invoiceRepository;
    private final SupplierInvoiceLineRepository lineRepository;
    private final SupplierRepository supplierRepository;
    private final InventoryItemRepository itemRepository;
    private final InventoryService inventoryService;
    private final RestaurantRepository restaurantRepository;
    private final AppUserRepository appUserRepository;
    private final AiService aiService;
    private final OcrService ocrService;
    private final AuditService auditService;

    private record DraftLine(String description, BigDecimal quantity, BigDecimal unitCost) {
    }

    @Transactional
    public SupplierInvoice scan(String imageBase64, String mimeType, java.util.UUID supplierId, java.util.UUID actorUserId) {
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst().orElse(null);
        Supplier supplier = supplierId == null ? null : supplierRepository.findById(supplierId).orElse(null);
        boolean aiConfigured = restaurant != null && restaurant.isAiFeaturesEnabled()
                && restaurant.getAiApiKey() != null && !restaurant.getAiApiKey().isBlank()
                && AiProvider.fromCode(restaurant.getAiProvider()) != null;
        boolean preferAiFirst = restaurant != null && restaurant.isOcrUseAiVisionAssist();

        String rawText = null;
        String method = "MANUAL";
        String note = "OCR was not attempted for this invoice (no image supplied) - enter items manually below.";
        List<DraftLine> draftLines = List.of();

        if (imageBase64 != null && !imageBase64.isBlank()) {
            byte[] imageBytes;
            try {
                imageBytes = Base64.getDecoder().decode(imageBase64);
            } catch (IllegalArgumentException ex) {
                imageBytes = null;
            }

            if (preferAiFirst && aiConfigured) {
                List<DraftLine> aiLines = tryAiVision(restaurant, imageBase64, mimeType);
                if (aiLines != null) {
                    method = "AI_VISION";
                    draftLines = aiLines;
                    rawText = "(AI vision extraction - " + aiLines.size() + " line(s) found)";
                    note = null;
                }
            }
            if ("MANUAL".equals(method) && imageBytes != null) {
                OcrExtractionResult ocr = ocrService.extractText(imageBytes);
                if (ocr.available()) {
                    method = "TESSERACT";
                    rawText = ocr.rawText();
                    draftLines = parseHeuristic(ocr.rawText());
                    note = draftLines.isEmpty()
                            ? "Tesseract read the image but no lines matched the simple qty/cost pattern - "
                            + "review the raw text below and enter items manually."
                            : null;
                } else if (aiConfigured) {
                    List<DraftLine> aiLines = tryAiVision(restaurant, imageBase64, mimeType);
                    if (aiLines != null) {
                        method = "AI_VISION";
                        draftLines = aiLines;
                        rawText = "(AI vision extraction, used as a fallback after OCR was unavailable - "
                                + aiLines.size() + " line(s) found)";
                        note = null;
                    } else {
                        note = ocr.unavailableReason();
                    }
                } else {
                    note = ocr.unavailableReason();
                }
            }
        }

        SupplierInvoice invoice = SupplierInvoice.builder()
                .supplier(supplier)
                .rawImageBase64(imageBase64)
                .extractedRawText(rawText)
                .extractionMethod(method)
                .status(SupplierInvoiceStatus.PENDING_REVIEW)
                .notes(note)
                .build();
        SupplierInvoice saved = invoiceRepository.save(invoice);

        List<SupplierInvoiceLine> lines = new ArrayList<>();
        for (DraftLine dl : draftLines) {
            lines.add(SupplierInvoiceLine.builder()
                    .invoice(saved)
                    .description(dl.description())
                    .quantity(dl.quantity())
                    .unitCost(dl.unitCost())
                    .rawText(dl.description())
                    .build());
        }
        if (!lines.isEmpty()) {
            lineRepository.saveAll(lines);
        }
        saved.setLines(lines);

        auditService.record(actorUserId, null, "SupplierInvoice", saved.getId(), "SCANNED", null,
                method + " (" + lines.size() + " draft line(s))", null, CorrelationIdHolder.get());
        return saved;
    }

    private List<DraftLine> tryAiVision(Restaurant restaurant, String imageBase64, String mimeType) {
        try {
            String raw = aiService.chatWithImage(restaurant, AI_SYSTEM_PROMPT,
                    "Extract every supplier invoice line item from this photo as the JSON array described.",
                    imageBase64, mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType);
            JsonNode array = MAPPER.readTree(AiService.stripCodeFence(raw));
            if (!array.isArray()) {
                return List.of();
            }
            List<DraftLine> lines = new ArrayList<>();
            for (JsonNode node : array) {
                String description = node.hasNonNull("description") ? node.get("description").asText() : null;
                if (description == null || description.isBlank()) {
                    continue;
                }
                BigDecimal quantity = node.hasNonNull("quantity") ? new BigDecimal(node.get("quantity").asText()) : null;
                BigDecimal unitCost = node.hasNonNull("unitCost") ? new BigDecimal(node.get("unitCost").asText()) : null;
                lines.add(new DraftLine(description.trim(), quantity, unitCost));
            }
            return lines;
        } catch (Exception ex) {
            log.warn("AI vision invoice extraction failed, falling back: {}", ex.toString());
            return null;
        }
    }

    private List<DraftLine> parseHeuristic(String rawText) {
        List<DraftLine> lines = new ArrayList<>();
        for (String line : rawText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher matcher = NUMBER.matcher(trimmed);
            List<String> numbers = new ArrayList<>();
            while (matcher.find()) {
                numbers.add(matcher.group());
            }
            BigDecimal quantity = null;
            BigDecimal unitCost = null;
            if (numbers.size() == 2 || numbers.size() == 3) {
                quantity = safeDecimal(numbers.get(0));
                unitCost = safeDecimal(numbers.get(1));
            }
            String description = NUMBER.matcher(trimmed).replaceAll("").replaceAll("\\s+", " ").trim();
            if (description.isEmpty()) {
                description = trimmed;
            }
            lines.add(new DraftLine(description, quantity, unitCost));
        }
        return lines;
    }

    private BigDecimal safeDecimal(String raw) {
        try {
            return new BigDecimal(raw.replace(",", "."));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<SupplierInvoice> listAll() {
        return invoiceRepository.findByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public SupplierInvoice get(java.util.UUID id) {
        return invoiceRepository.findById(id).orElseThrow(() -> ApiException.notFound("Supplier invoice not found"));
    }

    /**
     * The manager's reviewed, edited, and inventory-matched final line list REPLACES whatever was
     * extracted (same "replace-all" convention as {@code RecipeService#saveRecipe}) - a line is
     * only applied to inventory (stock RECEIVE + landed-cost update, both via the already-tested
     * {@link InventoryService}) when it has a matched item, a positive quantity, and a non-negative
     * unit cost; anything incomplete is simply skipped, never guessed at.
     */
    @Transactional
    public SupplierInvoice confirm(java.util.UUID id, List<SupplierInvoiceDtos.ConfirmLineRequest> lineRequests,
                                    String notes, long version, java.util.UUID actorUserId) {
        SupplierInvoice invoice = invoiceRepository.findById(id).orElseThrow(() -> ApiException.notFound("Supplier invoice not found"));
        if (invoice.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(SupplierInvoice.class, id);
        }
        if (invoice.getStatus() != SupplierInvoiceStatus.PENDING_REVIEW) {
            throw ApiException.conflict("INVOICE_ALREADY_DECIDED", "This invoice has already been " + invoice.getStatus() + ".");
        }
        if (lineRequests == null || lineRequests.isEmpty()) {
            throw ApiException.badRequest("NO_LINES", "At least one line is required to confirm an invoice.");
        }

        List<SupplierInvoiceLine> existing = lineRepository.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId());
        if (!existing.isEmpty()) {
            lineRepository.deleteAll(existing);
        }

        List<SupplierInvoiceLine> freshLines = new ArrayList<>();
        for (SupplierInvoiceDtos.ConfirmLineRequest lr : lineRequests) {
            InventoryItem item = lr.inventoryItemId() == null ? null : itemRepository.findById(lr.inventoryItemId()).orElse(null);
            freshLines.add(SupplierInvoiceLine.builder()
                    .invoice(invoice)
                    .inventoryItem(item)
                    .description(lr.description())
                    .quantity(lr.quantity())
                    .unitCost(lr.unitCost())
                    .build());
        }
        lineRepository.saveAll(freshLines);
        invoice.setLines(freshLines);

        int applied = 0;
        for (SupplierInvoiceLine line : freshLines) {
            if (line.getInventoryItem() == null || line.getQuantity() == null
                    || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                    || line.getUnitCost() == null || line.getUnitCost().compareTo(BigDecimal.ZERO) < 0) {
                continue;
            }
            java.util.UUID itemId = line.getInventoryItem().getId();
            InventoryItem freshItem = itemRepository.findById(itemId).orElse(null);
            if (freshItem == null) {
                continue;
            }
            String reason = "Supplier invoice" + (invoice.getSupplier() == null ? "" : " (" + invoice.getSupplier().getName() + ")");
            inventoryService.recordTransaction(itemId, "RECEIVE", line.getQuantity(), reason, freshItem.getVersion(), actorUserId);
            InventoryItem afterReceive = itemRepository.findById(itemId)
                    .orElseThrow(() -> ApiException.notFound("Inventory item not found"));
            inventoryService.updateItem(itemId, null, null, null, line.getUnitCost(), null, afterReceive.getVersion(), actorUserId);
            applied++;
        }

        invoice.setStatus(SupplierInvoiceStatus.CONFIRMED);
        invoice.setNotes(notes);
        invoice.setConfirmedBy(actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null));
        invoice.setConfirmedAt(LocalDateTime.now());
        SupplierInvoice saved = invoiceRepository.save(invoice);

        auditService.record(actorUserId, null, "SupplierInvoice", saved.getId(), "CONFIRMED", null,
                applied + " of " + freshLines.size() + " line(s) applied to inventory", notes, CorrelationIdHolder.get());
        return saved;
    }

    @Transactional
    public SupplierInvoice reject(java.util.UUID id, String notes, long version, java.util.UUID actorUserId) {
        SupplierInvoice invoice = invoiceRepository.findById(id).orElseThrow(() -> ApiException.notFound("Supplier invoice not found"));
        if (invoice.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(SupplierInvoice.class, id);
        }
        if (invoice.getStatus() != SupplierInvoiceStatus.PENDING_REVIEW) {
            throw ApiException.conflict("INVOICE_ALREADY_DECIDED", "This invoice has already been " + invoice.getStatus() + ".");
        }
        invoice.setStatus(SupplierInvoiceStatus.REJECTED);
        invoice.setNotes(notes);
        invoice.setConfirmedBy(actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null));
        invoice.setConfirmedAt(LocalDateTime.now());
        SupplierInvoice saved = invoiceRepository.save(invoice);

        auditService.record(actorUserId, null, "SupplierInvoice", saved.getId(), "REJECTED", null, null, notes, CorrelationIdHolder.get());
        return saved;
    }
}
