package com.chefpay.javafx.settings;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.BillingDtos;
import com.chefpay.javafx.client.dto.MenuDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.common.ReceiptPrinter;
import com.chefpay.javafx.common.UpiQrGenerator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Phase 5's Settings slice (ARCHITECTURE.md §14), expanded in the "full-fledged POS" round with
 * the config panels that were the actual gap behind that request: Tax/GST config and Discount
 * presets (backend existed since Phase 4, this was purely a missing UI - see {@code BillingView}'s
 * discount-preset picker for the other half of that gap), the "Store on/off Status" online-order
 * toggles, and a receipt footer text field. Deliberately still scoped short of a real printer/
 * template editor, a rounding rule, or a receipt logo - see {@code Restaurant}'s javadoc for why
 * those specific items are left out this round rather than shipped half-right.
 */
public class SettingsView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final Label statusLabel = new Label();

    private final TextField nameField = new TextField();
    private final TextField currencyField = new TextField();
    private final TextField gstinField = new TextField();
    private final TextField phoneField = new TextField();
    private final TextField serviceChargeField = new TextField();
    private final CheckBox kitchenSyncCheckbox = new CheckBox(
            "Require the kitchen to serve every item before a waiter can mark the order served");
    private final TextArea receiptFooterField = new TextArea();
    private final CheckBox zomatoCheckbox = new CheckBox("Accepting orders from Zomato");
    private final CheckBox swiggyCheckbox = new CheckBox("Accepting orders from Swiggy");
    private final CheckBox autoPrintOnlineOrdersCheckbox = new CheckBox("Auto-print a kitchen ticket the moment an online order comes in");
    private final CheckBox autoPrintReceiptOnPaymentCheckbox = new CheckBox(
            "Auto-print the receipt the instant a bill is paid in full (skips \"View Receipt\")");
    private final CheckBox deliveryBoyFeatureEnabledCheckbox = new CheckBox("Enable delivery boy assignment on online orders");
    private final CheckBox kotOptionalEnabledCheckbox = new CheckBox(
            "Allow billing without sending to kitchen (\"Bill Directly\" once physically verified)");

    // ---- Round 12: dashboard/kitchen/billing workflow configuration ----
    private final javafx.scene.control.ChoiceBox<String> dashboardViewModeChoice = new javafx.scene.control.ChoiceBox<>();
    private final javafx.scene.control.ChoiceBox<String> kitchenServiceModeChoice = new javafx.scene.control.ChoiceBox<>();
    private final CheckBox showDiscountConfirmationCheckbox = new CheckBox(
            "Show the \"discount can't be changed afterward\" warning before generating a bill");
    private final CheckBox poApprovalRequiredCheckbox = new CheckBox(
            "Require approval for Purchase Orders (a user with Purchase Order Approve permission still submits directly)");

    // ---- AI Features (Round 10) - bring-your-own-key, multi-provider (OpenAI / Anthropic / Gemini) ----
    private final CheckBox aiFeaturesEnabledCheckbox = new CheckBox("Enable AI Features");
    private final javafx.scene.control.ChoiceBox<String> aiProviderChoice = new javafx.scene.control.ChoiceBox<>();
    /** Always rendered blank - the saved key is never sent back to the client. Leaving this blank on
     * Save means "unchanged"; typing a new value replaces the saved key; checking
     * {@code clearApiKeyCheckbox} instead sends "" to actually remove a saved key - see
     * UpdateRestaurantRequest's javadoc for why blank and null mean different things here. */
    private final TextField aiApiKeyField = new TextField();
    private final Label aiApiKeyStatusLabel = new Label();
    private final CheckBox clearApiKeyCheckbox = new CheckBox("Clear saved API key");
    private final TextField aiModelField = new TextField();
    private final CheckBox aiMenuImportEnabledCheckbox = new CheckBox("AI Menu Setup - import a menu from a photo or PDF");
    private final CheckBox aiInsightsChatEnabledCheckbox = new CheckBox("\"Ask Your Data\" - chat with your sales & reports");
    private final CheckBox aiReorderDraftsEnabledCheckbox = new CheckBox("Smart reorder drafts for low-stock items");
    private final CheckBox aiAnomalyFlaggingEnabledCheckbox = new CheckBox("Audit anomaly flagging (unusual voids/discounts/complimentary)");
    private final CheckBox aiMenuDescriptionsEnabledCheckbox = new CheckBox("AI-written menu item descriptions");
    private final CheckBox aiNightlySummaryEnabledCheckbox = new CheckBox("Nightly AI summary notification");
    private final CheckBox aiReplenishmentNotesEnabledCheckbox = new CheckBox(
            "AI-written note on each inventory replenishment suggestion");
    private final CheckBox ocrUseAiVisionAssistCheckbox = new CheckBox(
            "Supplier invoice scanning: prefer AI vision over offline OCR when both are available");
    private final CheckBox biometricOverrideEnabledCheckbox = new CheckBox(
            "Allow biometric read as an alternative to PIN for Level-3 manager overrides (requires a biometric plugin - PIN always still works)");
    private final CheckBox autoPurgeEnabledCheckbox = new CheckBox(
            "Automatically purge old operational logs and old fully-paid, EOD-finalized orders/payments to keep the database size bounded "
                    + "(off by default - the audit log and any unreviewed/escalated/unfinalized records are never purged)");
    private final TextField dataRetentionDaysField = new TextField();

    // ---- Payment methods / UPI / card / printer / cash drawer (Round 6) ----
    private final CheckBox cashMethodCheckbox = new CheckBox("Cash");
    private final CheckBox cardMethodCheckbox = new CheckBox("Card");
    private final CheckBox upiMethodCheckbox = new CheckBox("UPI");
    private final CheckBox walletMethodCheckbox = new CheckBox("Wallet");
    private final CheckBox otherMethodCheckbox = new CheckBox("Other");
    private final TextField upiVpaField = new TextField();
    private final TextField upiPayeeNameField = new TextField();
    private final CheckBox cardPaymentEnabledCheckbox = new CheckBox("Accept card payments");
    private final TextField cardTerminalNoteField = new TextField();
    private final javafx.scene.control.ChoiceBox<String> printerNameChoice = new javafx.scene.control.ChoiceBox<>();
    private final TextField paperWidthField = new TextField();
    private final CheckBox cashDrawerEnabledCheckbox = new CheckBox("Open the cash drawer automatically on a cash sale");

    // ---- Receipt delivery via email (Round 11) ----
    private final TextField smtpHostField = new TextField();
    private final TextField smtpPortField = new TextField();
    private final TextField smtpUsernameField = new TextField();
    /** Same write-only-credential pattern as {@code aiApiKeyField} above: always rendered blank,
     * blank on Save means "unchanged", {@code clearSmtpPasswordCheckbox} sends "" to actually clear it. */
    private final TextField smtpPasswordField = new TextField();
    private final Label smtpPasswordStatusLabel = new Label();
    private final CheckBox clearSmtpPasswordCheckbox = new CheckBox("Clear saved SMTP password");
    private final TextField smtpFromAddressField = new TextField();
    private final CheckBox smtpUseTlsCheckbox = new CheckBox("Use STARTTLS (on for port 587, off for port 465)");

    // ---- Restaurant logo (Round 7) ----
    private final javafx.scene.image.ImageView logoPreview = new javafx.scene.image.ImageView();
    /** Base64 of whatever's currently "pending save" for the logo - initialized from the loaded
     * restaurant, overwritten by "Choose Image...", or set to "" (empty string, NOT null) by
     * "Remove Logo". Kept separate from {@code blankToNull}'s usual null-means-unchanged pattern
     * specifically so "Remove Logo" can actually clear a previously-set logo: an empty string still
     * reaches the server (only a real {@code null} means "leave unchanged" there), where it's
     * stored as blank and every screen that reads it treats blank the same as "no logo". */
    private String pendingLogoBase64;

    private final VBox taxRows = new VBox(8);
    private final VBox discountRows = new VBox(8);

    private RestaurantDtos.RestaurantDto currentRestaurant;

    public SettingsView(ApiClient apiClient) {
        this.apiClient = apiClient;
        root.setTop(buildHeader());
        root.setCenter(buildForm());
    }

    private HBox buildHeader() {
        Label title = new Label("Settings");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    private ScrollPane buildForm() {
        VBox form = new VBox(16);
        form.setPadding(new Insets(24));
        form.setMaxWidth(640);

        form.getChildren().addAll(
                sectionLabel("Restaurant Profile"),
                field("Restaurant Name", nameField),
                field("Currency Symbol", currencyField),
                field("GSTIN", gstinField),
                field("Support Phone", phoneField),
                field("Service Charge %", serviceChargeField));

        logoPreview.setFitWidth(80);
        logoPreview.setFitHeight(80);
        logoPreview.setPreserveRatio(true);
        logoPreview.setSmooth(true);
        Button chooseLogoButton = new Button("Choose Image...");
        chooseLogoButton.setOnAction(e -> chooseLogo(chooseLogoButton.getScene() == null ? null : chooseLogoButton.getScene().getWindow()));
        Button removeLogoButton = new Button("Remove Logo");
        removeLogoButton.setOnAction(e -> removeLogo());
        Label logoHelp = new Label("Shown at the top of the Dashboard screen. PNG or JPG under 500 KB works best.");
        logoHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        VBox logoButtonsBox = new VBox(6, new HBox(8, chooseLogoButton, removeLogoButton), logoHelp);
        HBox logoRow = new HBox(16, logoPreview, logoButtonsBox);
        logoRow.setAlignment(Pos.CENTER_LEFT);
        form.getChildren().addAll(sectionLabel("Restaurant Logo"), logoRow);

        kitchenSyncCheckbox.setWrapText(true);
        kitchenSyncCheckbox.setMaxWidth(560);
        Label kitchenSyncHelp = new Label(
                "When off, a waiter can mark an order served as soon as everything's been sent to "
                        + "the kitchen, regardless of what the kitchen has actually done with it - "
                        + "fine for a small team running both ends, risky once floor and kitchen are "
                        + "different people who need to stay honestly in sync.");
        kitchenSyncHelp.setWrapText(true);
        kitchenSyncHelp.setMaxWidth(560);
        kitchenSyncHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        kotOptionalEnabledCheckbox.setWrapText(true);
        kotOptionalEnabledCheckbox.setMaxWidth(560);
        Label kotOptionalHelp = new Label(
                "When on, a waiter can tap \"Bill Directly\" on a newly-placed order instead of \"Send "
                        + "to Kitchen\" - useful once staff have already physically confirmed the order with "
                        + "the kitchen themselves. That order skips the Kitchen Display entirely: no KOT "
                        + "ticket is printed/assigned and the kitchen is never notified, so only use this for "
                        + "orders truly verified in person.");
        kotOptionalHelp.setWrapText(true);
        kotOptionalHelp.setMaxWidth(560);
        kotOptionalHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        kitchenServiceModeChoice.getItems().addAll("DETAILED", "SIMPLE");
        kitchenServiceModeChoice.setValue("DETAILED");
        Label kitchenModeHelp = new Label(
                "Detailed: kitchen staff advance each item Accept -> Start -> Ready -> Served one step at a "
                        + "time (today's behavior). Simple: one \"Serve All\" action per ticket marks every item "
                        + "served in one tap - see the Kitchen Display screen.");
        kitchenModeHelp.setWrapText(true);
        kitchenModeHelp.setMaxWidth(560);
        kitchenModeHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        form.getChildren().addAll(sectionLabel("Kitchen Operations"), kitchenSyncCheckbox, kitchenSyncHelp,
                kotOptionalEnabledCheckbox, kotOptionalHelp,
                field("Kitchen Service Mode", kitchenServiceModeChoice), kitchenModeHelp);

        dashboardViewModeChoice.getItems().addAll("STANDARD", "GRAPHICAL", "BOTH");
        dashboardViewModeChoice.setValue("STANDARD");
        Label dashboardModeHelp = new Label(
                "Standard: today's plain KPI-card dashboard. Graphical: charts (sales trend, category "
                        + "split, top items, payment methods). Both: shows both sections.");
        dashboardModeHelp.setWrapText(true);
        dashboardModeHelp.setMaxWidth(560);
        dashboardModeHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.getChildren().addAll(sectionLabel("Dashboard"), field("Dashboard View", dashboardViewModeChoice), dashboardModeHelp);

        showDiscountConfirmationCheckbox.setWrapText(true);
        showDiscountConfirmationCheckbox.setMaxWidth(560);
        form.getChildren().addAll(sectionLabel("Billing Workflow"), showDiscountConfirmationCheckbox);

        poApprovalRequiredCheckbox.setWrapText(true);
        poApprovalRequiredCheckbox.setMaxWidth(560);
        Label poApprovalHelp = new Label(
                "Per-role approval is controlled from Role Management (\"Purchase Order Approve\" "
                        + "permission) - a manager holding that permission always submits a PO directly; "
                        + "anyone else's PO waits for one of those managers to approve it, when this is on.");
        poApprovalHelp.setWrapText(true);
        poApprovalHelp.setMaxWidth(560);
        poApprovalHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.getChildren().addAll(sectionLabel("Purchase Orders"), poApprovalRequiredCheckbox, poApprovalHelp);

        receiptFooterField.setPromptText("Thank you, visit again!");
        receiptFooterField.setPrefRowCount(2);
        receiptFooterField.setWrapText(true);
        receiptFooterField.setMaxWidth(560);
        Label receiptHelp = new Label("Printed as the closing line on every receipt. Leave blank to use the default.");
        receiptHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.getChildren().addAll(sectionLabel("Receipt"), field("Receipt Footer Text", receiptFooterField), receiptHelp);

        zomatoCheckbox.setWrapText(true);
        swiggyCheckbox.setWrapText(true);
        Label onlineHelp = new Label(
                "Store on/off status for each aggregator. This is a record-only toggle today - it does "
                        + "not yet connect to a live Zomato/Swiggy order feed (that needs real API "
                        + "credentials and webhooks, a separate integration project).");
        onlineHelp.setWrapText(true);
        onlineHelp.setMaxWidth(560);
        onlineHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        autoPrintOnlineOrdersCheckbox.setWrapText(true);
        autoPrintOnlineOrdersCheckbox.setMaxWidth(560);
        form.getChildren().addAll(sectionLabel("Online Ordering"), zomatoCheckbox, swiggyCheckbox, onlineHelp,
                autoPrintOnlineOrdersCheckbox);

        deliveryBoyFeatureEnabledCheckbox.setWrapText(true);
        deliveryBoyFeatureEnabledCheckbox.setMaxWidth(560);
        Label deliveryBoyHelp = new Label(
                "When on, the Online Orders screen shows a picker to assign a rider from the "
                        + "Delivery Boys roster to each order. Off by default - a dine-in-only "
                        + "restaurant has no use for this.");
        deliveryBoyHelp.setWrapText(true);
        deliveryBoyHelp.setMaxWidth(560);
        deliveryBoyHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.getChildren().addAll(sectionLabel("Delivery"), deliveryBoyFeatureEnabledCheckbox, deliveryBoyHelp);

        form.getChildren().addAll(sectionLabel("Payment Methods"), buildPaymentMethodsRow());

        Label upiHelp = new Label(
                "Generates a \"scan to pay\" QR on Record Payment (any UPI app - PhonePe, Google Pay, "
                        + "Paytm, BHIM, etc. - can scan it). This does NOT confirm payment automatically - "
                        + "staff still checks the UPI app/bank SMS and marks it received, the same as a "
                        + "card payment today. No payment gateway or bank account access is used.");
        upiHelp.setWrapText(true);
        upiHelp.setMaxWidth(560);
        upiHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        upiVpaField.setPromptText("yourrestaurant@okhdfcbank");
        Button previewQr = new Button("Preview QR");
        previewQr.setOnAction(e -> previewUpiQr());
        HBox upiVpaRow = new HBox(8, upiVpaField, previewQr);
        HBox.setHgrow(upiVpaField, Priority.ALWAYS);
        form.getChildren().addAll(sectionLabel("UPI / QR Payments"),
                labeledRow("UPI ID (VPA)", upiVpaRow), field("Payee Display Name", upiPayeeNameField), upiHelp);

        cardTerminalNoteField.setPromptText("e.g. Pine Labs terminal at Counter 1 (optional)");
        Label cardHelp = new Label("Informational only - recording a card payment still just logs the amount, "
                + "same as today. No card network/payment-gateway integration.");
        cardHelp.setWrapText(true);
        cardHelp.setMaxWidth(560);
        cardHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.getChildren().addAll(sectionLabel("Card Payments"), cardPaymentEnabledCheckbox,
                field("Terminal Note", cardTerminalNoteField), cardHelp);

        printerNameChoice.getItems().setAll(ReceiptPrinter.availablePrinterNames());
        printerNameChoice.setPrefWidth(280);
        Button refreshPrinters = new Button("Refresh List");
        refreshPrinters.setOnAction(e -> printerNameChoice.getItems().setAll(ReceiptPrinter.availablePrinterNames()));
        HBox printerRow = new HBox(8, printerNameChoice, refreshPrinters);
        paperWidthField.setPromptText("40");
        Label printerHelp = new Label(
                "Used for silent/unattended printing only - auto-printed online-order KOTs, an "
                        + "auto-printed receipt on full payment (below), and the cash drawer kick below. The "
                        + "regular \"Print Bill\"/\"Print Receipt\" buttons still open the normal OS print "
                        + "dialog so you can pick any printer.");
        printerHelp.setWrapText(true);
        printerHelp.setMaxWidth(560);
        printerHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        autoPrintReceiptOnPaymentCheckbox.setWrapText(true);
        autoPrintReceiptOnPaymentCheckbox.setMaxWidth(560);
        Label autoPrintReceiptHelp = new Label(
                "Requires a Default Printer above. When a bill's balance due reaches zero, the receipt "
                        + "prints immediately to that printer - no dialog, no separate click. If the printer "
                        + "can't be reached, Billing falls back to today's \"View Receipt\" button so the "
                        + "receipt is never lost.");
        autoPrintReceiptHelp.setWrapText(true);
        autoPrintReceiptHelp.setMaxWidth(560);
        autoPrintReceiptHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.getChildren().addAll(sectionLabel("Receipt / KOT Printer"),
                labeledRow("Default Printer", printerRow), field("Paper Width (characters)", paperWidthField), printerHelp,
                autoPrintReceiptOnPaymentCheckbox, autoPrintReceiptHelp);

        Label drawerHelp = new Label(
                "Sends a raw \"kick drawer\" pulse to the printer above. Only works with an ESC/POS-"
                        + "compatible thermal receipt printer that has the drawer wired through its kick-out "
                        + "port - the standard setup. Against any other printer this does nothing useful.");
        drawerHelp.setWrapText(true);
        drawerHelp.setMaxWidth(560);
        drawerHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.getChildren().addAll(sectionLabel("Cash Drawer"), cashDrawerEnabledCheckbox, drawerHelp);

        // Round 11 - "email receipt option": every restaurant brings its own SMTP account (a free
        // Gmail/Outlook address with an app password works fine at typical single-location volume).
        smtpHostField.setPromptText("e.g. smtp.gmail.com");
        smtpPortField.setPromptText("587");
        smtpUsernameField.setPromptText("usually your full email address");
        smtpPasswordField.setPromptText("Paste your SMTP password / app password here");
        smtpPasswordStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        clearSmtpPasswordCheckbox.setWrapText(true);
        clearSmtpPasswordCheckbox.setMaxWidth(560);
        smtpFromAddressField.setPromptText("usually the same as the username above");
        smtpUseTlsCheckbox.setSelected(true);
        Label smtpHelp = new Label(
                "Used by the \"Email Receipt\" button in Billing. A blank Host means email receipts "
                        + "aren't available yet - staff will only see \"View Receipt\"/\"WhatsApp Receipt\". "
                        + "Your password is sent directly to the SMTP host you configure and is never shown "
                        + "again after saving - only whether one is configured is displayed here.");
        smtpHelp.setWrapText(true);
        smtpHelp.setMaxWidth(560);
        smtpHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.getChildren().addAll(sectionLabel("Receipt Delivery - Email"),
                field("SMTP Host", smtpHostField), field("SMTP Port", smtpPortField),
                field("SMTP Username", smtpUsernameField),
                field("SMTP Password", smtpPasswordField), smtpPasswordStatusLabel, clearSmtpPasswordCheckbox,
                field("From Address", smtpFromAddressField), smtpUseTlsCheckbox, smtpHelp);

        aiFeaturesEnabledCheckbox.setWrapText(true);
        aiFeaturesEnabledCheckbox.setMaxWidth(560);
        Label aiMasterHelp = new Label(
                "Turns on the AI features below. Needs a provider and API key saved first - each "
                        + "feature also has its own switch so you only turn on what you'll actually use.");
        aiMasterHelp.setWrapText(true);
        aiMasterHelp.setMaxWidth(560);
        aiMasterHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        aiProviderChoice.getItems().setAll("OpenAI", "Anthropic (Claude)", "Google Gemini");
        aiProviderChoice.setPrefWidth(220);
        aiApiKeyField.setPromptText("Paste your API key here");
        aiApiKeyStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        clearApiKeyCheckbox.setWrapText(true);
        clearApiKeyCheckbox.setMaxWidth(560);
        aiModelField.setPromptText("optional, e.g. gpt-4o-mini / claude-3-5-haiku / gemini-1.5-flash");
        Label aiKeyHelp = new Label(
                "Your key is sent directly to the provider you choose and is never shown again after "
                        + "saving - only whether a key is configured is displayed here. Nothing is sent "
                        + "anywhere unless AI Features is on above and the specific feature below is too.");
        aiKeyHelp.setWrapText(true);
        aiKeyHelp.setMaxWidth(560);
        aiKeyHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        aiMenuImportEnabledCheckbox.setWrapText(true);
        aiMenuImportEnabledCheckbox.setMaxWidth(560);
        aiInsightsChatEnabledCheckbox.setWrapText(true);
        aiInsightsChatEnabledCheckbox.setMaxWidth(560);
        aiReorderDraftsEnabledCheckbox.setWrapText(true);
        aiReorderDraftsEnabledCheckbox.setMaxWidth(560);
        aiAnomalyFlaggingEnabledCheckbox.setWrapText(true);
        aiAnomalyFlaggingEnabledCheckbox.setMaxWidth(560);
        aiMenuDescriptionsEnabledCheckbox.setWrapText(true);
        aiMenuDescriptionsEnabledCheckbox.setMaxWidth(560);
        aiNightlySummaryEnabledCheckbox.setWrapText(true);
        aiNightlySummaryEnabledCheckbox.setMaxWidth(560);
        aiReplenishmentNotesEnabledCheckbox.setWrapText(true);
        aiReplenishmentNotesEnabledCheckbox.setMaxWidth(560);
        ocrUseAiVisionAssistCheckbox.setWrapText(true);
        ocrUseAiVisionAssistCheckbox.setMaxWidth(560);
        biometricOverrideEnabledCheckbox.setWrapText(true);
        biometricOverrideEnabledCheckbox.setMaxWidth(560);
        autoPurgeEnabledCheckbox.setWrapText(true);
        autoPurgeEnabledCheckbox.setMaxWidth(560);
        dataRetentionDaysField.setPromptText("30");

        form.getChildren().addAll(
                sectionLabel("AI Features"),
                aiFeaturesEnabledCheckbox, aiMasterHelp,
                labeledRow("AI Provider", aiProviderChoice),
                field("API Key", aiApiKeyField), aiApiKeyStatusLabel, clearApiKeyCheckbox,
                field("Model (optional)", aiModelField), aiKeyHelp,
                aiMenuImportEnabledCheckbox,
                aiInsightsChatEnabledCheckbox,
                aiReorderDraftsEnabledCheckbox,
                aiAnomalyFlaggingEnabledCheckbox,
                aiMenuDescriptionsEnabledCheckbox,
                aiNightlySummaryEnabledCheckbox,
                aiReplenishmentNotesEnabledCheckbox,
                ocrUseAiVisionAssistCheckbox,
                sectionLabel("Security"),
                biometricOverrideEnabledCheckbox,
                sectionLabel("Data Retention"),
                autoPurgeEnabledCheckbox,
                field("Keep records for at least (days)", dataRetentionDaysField));

        Button save = new Button("Save Changes");
        save.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 10 24;");
        save.setOnAction(e -> save());
        HBox saveRow = new HBox(save);
        saveRow.setPadding(new Insets(16, 0, 16, 0));
        form.getChildren().add(saveRow);

        form.getChildren().addAll(sectionLabel("Tax / GST Configuration"), buildTaxSection());
        form.getChildren().addAll(sectionLabel("Discount Presets"), buildDiscountSection());

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        return scroll;
    }

    // ---- Tax config ----

    private VBox buildTaxSection() {
        Button addTax = new Button("+ New Tax");
        addTax.setOnAction(e -> newTaxDialog());
        HBox row = new HBox(addTax);
        row.setPadding(new Insets(0, 0, 8, 0));
        VBox box = new VBox(8, row, taxRows);
        return box;
    }

    private void loadTaxes() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/billing/taxes");
                List<BillingDtos.TaxDto> taxes = apiClient.convertList(data, BillingDtos.TaxDto.class);
                Platform.runLater(() -> renderTaxes(taxes));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load taxes: " + ex.getMessage()));
            }
        }, "chefpay-settings-taxes-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderTaxes(List<BillingDtos.TaxDto> taxes) {
        taxRows.getChildren().clear();
        if (taxes.isEmpty()) {
            taxRows.getChildren().add(new Label("No taxes configured yet."));
            return;
        }
        for (BillingDtos.TaxDto tax : taxes) {
            Label name = new Label(tax.name() + (tax.defaultRate() ? "  (default)" : ""));
            name.setPrefWidth(200);
            Label code = new Label(tax.code());
            code.setPrefWidth(80);
            Label rate = new Label(tax.ratePercent() + "%");
            rate.setPrefWidth(70);
            Label status = new Label(tax.active() ? "Active" : "Inactive");
            status.setStyle(tax.active() ? "-fx-text-fill: #27ae60;" : "-fx-text-fill: #999;");
            status.setPrefWidth(70);

            HBox spacer = new HBox();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editTaxDialog(tax));

            HBox taxRow = new HBox(10, name, code, rate, status, spacer, edit);
            taxRow.setAlignment(Pos.CENTER_LEFT);
            taxRow.setPadding(new Insets(6, 8, 6, 8));
            taxRow.setStyle("-fx-border-color: #eee; -fx-border-width: 0 0 1 0;");
            taxRows.getChildren().add(taxRow);
        }
    }

    private void newTaxDialog() {
        Dialog<BillingDtos.CreateTaxRequest> dialog = new Dialog<>();
        dialog.setTitle("New Tax");
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText("e.g. GST 5%");
        TextField code = new TextField();
        code.setPromptText("e.g. GST5");
        TextField rate = new TextField();
        rate.setPromptText("e.g. 5");
        CheckBox defaultRate = new CheckBox("Apply by default to new bills");
        Label defaultRateHelp = new Label("If this is your first tax rate it will be applied by default "
                + "automatically, even if left unchecked. Leave items' Tax Code blank to use whichever "
                + "tax is marked default.");
        defaultRateHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        defaultRateHelp.setWrapText(true);
        defaultRateHelp.setMaxWidth(280);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Code"), code);
        grid.addRow(2, new Label("Rate %"), rate);
        grid.addRow(3, new Label(""), defaultRate);
        grid.addRow(4, new Label(""), defaultRateHelp);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (name.getText().isBlank() || code.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter a name and code.").showAndWait();
                return null;
            }
            try {
                BigDecimal ratePercent = new BigDecimal(rate.getText().trim());
                return new BillingDtos.CreateTaxRequest(name.getText().trim(), code.getText().trim(),
                        ratePercent, defaultRate.isSelected());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid rate percentage.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/billing/taxes", request);
                    Platform.runLater(this::loadTaxes);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-tax-create");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void editTaxDialog(BillingDtos.TaxDto tax) {
        Dialog<BillingDtos.UpdateTaxRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit Tax - " + tax.name());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField name = new TextField(tax.name());
        TextField rate = new TextField(tax.ratePercent().toPlainString());
        CheckBox active = new CheckBox("Active");
        active.setSelected(tax.active());
        CheckBox defaultRate = new CheckBox("Apply by default to new bills");
        defaultRate.setSelected(tax.defaultRate());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Rate %"), rate);
        grid.addRow(2, new Label(""), active);
        grid.addRow(3, new Label(""), defaultRate);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Name cannot be blank.").showAndWait();
                return null;
            }
            try {
                BigDecimal ratePercent = new BigDecimal(rate.getText().trim());
                return new BillingDtos.UpdateTaxRequest(name.getText().trim(), ratePercent,
                        active.isSelected(), defaultRate.isSelected(), tax.version());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid rate percentage.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.patch("/api/billing/taxes/" + tax.id(), request);
                    Platform.runLater(this::loadTaxes);
                } catch (ApiException ex) {
                    Platform.runLater(() -> {
                        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                            loadTaxes();
                            new Alert(Alert.AlertType.WARNING, "This tax changed elsewhere - showing the latest version. Please retry.").showAndWait();
                        } else {
                            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                        }
                    });
                }
            }, "chefpay-tax-update");
            worker.setDaemon(true);
            worker.start();
        });
    }

    // ---- Discount presets ----

    private VBox buildDiscountSection() {
        Button addDiscount = new Button("+ New Discount Preset");
        addDiscount.setOnAction(e -> newDiscountDialog());
        HBox row = new HBox(addDiscount);
        row.setPadding(new Insets(0, 0, 8, 0));
        return new VBox(8, row, discountRows);
    }

    private void loadDiscounts() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/billing/discounts");
                List<BillingDtos.DiscountDto> discounts = apiClient.convertList(data, BillingDtos.DiscountDto.class);
                Platform.runLater(() -> renderDiscounts(discounts));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load discounts: " + ex.getMessage()));
            }
        }, "chefpay-settings-discounts-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderDiscounts(List<BillingDtos.DiscountDto> discounts) {
        discountRows.getChildren().clear();
        if (discounts.isEmpty()) {
            discountRows.getChildren().add(new Label("No discount presets configured yet."));
            return;
        }
        for (BillingDtos.DiscountDto discount : discounts) {
            Label name = new Label(discount.name());
            name.setPrefWidth(200);
            Label type = new Label(discount.type());
            type.setPrefWidth(90);
            Label value = new Label("PERCENTAGE".equals(discount.type()) ? discount.value() + "%" : discount.value().toPlainString());
            value.setPrefWidth(90);
            String scopeText = (discount.applicableCategoryName() != null ? discount.applicableCategoryName() : "Whole Bill")
                    + (discount.maxDiscountAmount() != null ? " (cap " + discount.maxDiscountAmount().toPlainString() + ")" : "");
            Label scope = new Label(scopeText);
            scope.setPrefWidth(180);
            scope.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
            Label status = new Label(discount.active() ? "Active" : "Inactive");
            status.setStyle(discount.active() ? "-fx-text-fill: #27ae60;" : "-fx-text-fill: #999;");
            status.setPrefWidth(70);

            HBox spacer = new HBox();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editDiscountDialog(discount));

            HBox discountRow = new HBox(10, name, type, value, scope, status, spacer, edit);
            discountRow.setAlignment(Pos.CENTER_LEFT);
            discountRow.setPadding(new Insets(6, 8, 6, 8));
            discountRow.setStyle("-fx-border-color: #eee; -fx-border-width: 0 0 1 0;");
            discountRows.getChildren().add(discountRow);
        }
    }

    /** Fetches menu categories (name -&gt; id, insertion order preserved) for the discount dialogs'
     * "applies to" picker - a category-scoped preset only discounts that category's line items
     * (see {@code Discount.applicableCategory}'s javadoc). Falls back to an empty map (whole-bill-
     * only picker) rather than blocking the dialog if the menu can't be loaded. */
    private void loadCategoriesThen(Consumer<Map<String, UUID>> callback) {
        Thread worker = new Thread(() -> {
            Map<String, UUID> byName = new LinkedHashMap<>();
            try {
                var data = apiClient.get("/api/menu");
                List<MenuDtos.CategoryDto> categories = apiClient.convertList(data, MenuDtos.CategoryDto.class);
                for (MenuDtos.CategoryDto c : categories) {
                    byName.put(c.name(), c.id());
                }
            } catch (ApiException ignored) {
                // fall back to whole-bill-only picker below
            }
            Platform.runLater(() -> callback.accept(byName));
        }, "chefpay-settings-categories-load");
        worker.setDaemon(true);
        worker.start();
    }

    private static final String WHOLE_BILL_LABEL = "(Whole Bill)";

    private void newDiscountDialog() {
        loadCategoriesThen(categoriesByName -> {
            Dialog<BillingDtos.CreateDiscountRequest> dialog = new Dialog<>();
            dialog.setTitle("New Discount Preset");
            ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

            TextField name = new TextField();
            name.setPromptText("e.g. Staff Discount");
            javafx.scene.control.ChoiceBox<String> type = new javafx.scene.control.ChoiceBox<>();
            type.getItems().addAll("PERCENTAGE", "FIXED_AMOUNT");
            type.setValue("PERCENTAGE");
            TextField value = new TextField();
            value.setPromptText("e.g. 10");
            TextField maxCap = new TextField();
            maxCap.setPromptText("Optional - e.g. 200 (blank = no cap)");
            javafx.scene.control.ChoiceBox<String> category = new javafx.scene.control.ChoiceBox<>();
            category.getItems().add(WHOLE_BILL_LABEL);
            category.getItems().addAll(categoriesByName.keySet());
            category.setValue(WHOLE_BILL_LABEL);

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(16));
            grid.addRow(0, new Label("Name"), name);
            grid.addRow(1, new Label("Type"), type);
            grid.addRow(2, new Label("Value"), value);
            grid.addRow(3, new Label("Max Cap"), maxCap);
            grid.addRow(4, new Label("Applies To"), category);
            dialog.getDialogPane().setContent(grid);

            dialog.setResultConverter(button -> {
                if (button != createType) {
                    return null;
                }
                if (name.getText().isBlank()) {
                    new Alert(Alert.AlertType.ERROR, "Enter a preset name.").showAndWait();
                    return null;
                }
                try {
                    BigDecimal v = new BigDecimal(value.getText().trim());
                    BigDecimal cap = maxCap.getText().isBlank() ? null : new BigDecimal(maxCap.getText().trim());
                    UUID categoryId = categoriesByName.get(category.getValue());
                    return new BillingDtos.CreateDiscountRequest(name.getText().trim(), type.getValue(), v, cap, categoryId);
                } catch (NumberFormatException ex) {
                    new Alert(Alert.AlertType.ERROR, "Enter a valid value/cap.").showAndWait();
                    return null;
                }
            });

            dialog.showAndWait().ifPresent(request -> {
                Thread worker = new Thread(() -> {
                    try {
                        apiClient.post("/api/billing/discounts", request);
                        Platform.runLater(this::loadDiscounts);
                    } catch (ApiException ex) {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                    }
                }, "chefpay-discount-create");
                worker.setDaemon(true);
                worker.start();
            });
        });
    }

    private void editDiscountDialog(BillingDtos.DiscountDto discount) {
        loadCategoriesThen(categoriesByName -> editDiscountDialogWithCategories(discount, categoriesByName));
    }

    private void editDiscountDialogWithCategories(BillingDtos.DiscountDto discount, Map<String, UUID> categoriesByName) {
        Dialog<BillingDtos.UpdateDiscountRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit Discount - " + discount.name());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField name = new TextField(discount.name());
        TextField value = new TextField(discount.value().toPlainString());
        TextField maxCap = new TextField(discount.maxDiscountAmount() == null ? "" : discount.maxDiscountAmount().toPlainString());
        maxCap.setPromptText("Blank = no cap");
        javafx.scene.control.ChoiceBox<String> category = new javafx.scene.control.ChoiceBox<>();
        category.getItems().add(WHOLE_BILL_LABEL);
        category.getItems().addAll(categoriesByName.keySet());
        category.setValue(discount.applicableCategoryName() != null ? discount.applicableCategoryName() : WHOLE_BILL_LABEL);
        CheckBox active = new CheckBox("Active");
        active.setSelected(discount.active());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Value (" + discount.type() + ")"), value);
        grid.addRow(2, new Label("Max Cap"), maxCap);
        grid.addRow(3, new Label("Applies To"), category);
        grid.addRow(4, new Label(""), active);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Name cannot be blank.").showAndWait();
                return null;
            }
            try {
                BigDecimal v = new BigDecimal(value.getText().trim());
                BigDecimal cap = maxCap.getText().isBlank() ? null : new BigDecimal(maxCap.getText().trim());
                boolean clearCategory = WHOLE_BILL_LABEL.equals(category.getValue());
                UUID categoryId = clearCategory ? null : categoriesByName.get(category.getValue());
                return new BillingDtos.UpdateDiscountRequest(name.getText().trim(), v, cap, categoryId, clearCategory,
                        active.isSelected(), discount.version());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid value/cap.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.patch("/api/billing/discounts/" + discount.id(), request);
                    Platform.runLater(this::loadDiscounts);
                } catch (ApiException ex) {
                    Platform.runLater(() -> {
                        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                            loadDiscounts();
                            new Alert(Alert.AlertType.WARNING, "This discount changed elsewhere - showing the latest version. Please retry.").showAndWait();
                        } else {
                            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                        }
                    });
                }
            }, "chefpay-discount-update");
            worker.setDaemon(true);
            worker.start();
        });
    }

    // ---- Restaurant profile ----

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        return label;
    }

    private VBox field(String labelText, javafx.scene.control.Control input) {
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        return new VBox(4, label, input);
    }

    private VBox labeledRow(String labelText, javafx.scene.Node row) {
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        return new VBox(4, label, row);
    }

    private HBox buildPaymentMethodsRow() {
        HBox row = new HBox(16, cashMethodCheckbox, cardMethodCheckbox, upiMethodCheckbox, walletMethodCheckbox, otherMethodCheckbox);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Lets staff test the UPI ID they just typed (before saving) - builds the same QR
     * {@code BillingView}'s Record Payment dialog would show, for a sample ₹1.00 request. */
    private void previewUpiQr() {
        String vpa = upiVpaField.getText();
        if (vpa == null || vpa.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "Enter a UPI ID first.").showAndWait();
            return;
        }
        String payeeName = upiPayeeNameField.getText() == null || upiPayeeNameField.getText().isBlank()
                ? (nameField.getText() == null || nameField.getText().isBlank() ? "Restaurant" : nameField.getText())
                : upiPayeeNameField.getText();
        String uri = UpiQrGenerator.buildUpiUri(vpa.trim(), payeeName, BigDecimal.ONE, "Preview", "PREVIEW");
        javafx.scene.image.ImageView imageView;
        try {
            imageView = new javafx.scene.image.ImageView(UpiQrGenerator.generate(uri, 260));
        } catch (IllegalStateException ex) {
            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
            return;
        }
        VBox content = new VBox(10, imageView, new Label(vpa.trim()));
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(16));
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("UPI QR Preview (sample ₹1.00)");
        alert.setHeaderText(null);
        alert.getDialogPane().setContent(content);
        alert.getButtonTypes().setAll(ButtonType.CLOSE);
        alert.showAndWait();
    }

    /** Reads the chosen file straight into memory as Base64 - no upload endpoint exists (or is
     * needed) in this app; the logo just rides along inside the same {@code PUT /api/restaurant}
     * every other Settings field already uses. Rejects anything over 500 KB up front rather than
     * silently bloating every future restaurant-config fetch across the app (Dashboard, Billing,
     * OrderTakingView, ShellView's auto-print handler, etc. all fetch that same payload). */
    private void chooseLogo(javafx.stage.Window owner) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Choose Restaurant Logo");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
        java.io.File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            if (bytes.length > 500_000) {
                new Alert(Alert.AlertType.WARNING, "That image is " + (bytes.length / 1024)
                        + " KB - please choose one under 500 KB.").showAndWait();
                return;
            }
            pendingLogoBase64 = java.util.Base64.getEncoder().encodeToString(bytes);
            logoPreview.setImage(new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes)));
        } catch (java.io.IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Could not read that file: " + ex.getMessage()).showAndWait();
        }
    }

    private void removeLogo() {
        pendingLogoBase64 = "";
        logoPreview.setImage(null);
    }

    public void reload() {
        statusLabel.setText("Loading...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(data, RestaurantDtos.RestaurantDto.class);
                Platform.runLater(() -> renderRestaurant(restaurant));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load settings: " + ex.getMessage()));
            }
        }, "chefpay-settings-load");
        worker.setDaemon(true);
        worker.start();
        loadTaxes();
        loadDiscounts();
    }

    private void renderRestaurant(RestaurantDtos.RestaurantDto restaurant) {
        this.currentRestaurant = restaurant;
        statusLabel.setText("");
        pendingLogoBase64 = restaurant.logoImageBase64();
        if (restaurant.logoImageBase64() != null && !restaurant.logoImageBase64().isBlank()) {
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(restaurant.logoImageBase64());
                logoPreview.setImage(new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes)));
            } catch (IllegalArgumentException ex) {
                logoPreview.setImage(null);
            }
        } else {
            logoPreview.setImage(null);
        }
        nameField.setText(restaurant.name());
        currencyField.setText(restaurant.currencySymbol());
        gstinField.setText(restaurant.gstin() == null ? "" : restaurant.gstin());
        phoneField.setText(restaurant.supportPhone() == null ? "" : restaurant.supportPhone());
        serviceChargeField.setText(restaurant.serviceChargePercent() == null ? "0" : restaurant.serviceChargePercent().toPlainString());
        kitchenSyncCheckbox.setSelected(restaurant.requireKitchenSyncForServed());
        receiptFooterField.setText(restaurant.receiptFooterText() == null ? "" : restaurant.receiptFooterText());
        zomatoCheckbox.setSelected(restaurant.onlineOrderZomatoEnabled());
        swiggyCheckbox.setSelected(restaurant.onlineOrderSwiggyEnabled());
        autoPrintOnlineOrdersCheckbox.setSelected(restaurant.autoPrintOnlineOrders());
        autoPrintReceiptOnPaymentCheckbox.setSelected(restaurant.autoPrintReceiptOnPayment());
        deliveryBoyFeatureEnabledCheckbox.setSelected(restaurant.deliveryBoyFeatureEnabled());
        kotOptionalEnabledCheckbox.setSelected(restaurant.kotOptionalEnabled());

        smtpHostField.setText(restaurant.smtpHost() == null ? "" : restaurant.smtpHost());
        smtpPortField.setText(restaurant.smtpPort() == null ? "587" : restaurant.smtpPort().toString());
        smtpUsernameField.setText(restaurant.smtpUsername() == null ? "" : restaurant.smtpUsername());
        smtpPasswordField.setText("");
        clearSmtpPasswordCheckbox.setSelected(false);
        smtpPasswordStatusLabel.setText(restaurant.smtpPasswordConfigured()
                ? "SMTP password configured (leave blank to keep it, or check \"Clear saved SMTP password\" to remove it)"
                : "No SMTP password configured yet");
        smtpFromAddressField.setText(restaurant.smtpFromAddress() == null ? "" : restaurant.smtpFromAddress());
        smtpUseTlsCheckbox.setSelected(restaurant.smtpUseTls());

        aiFeaturesEnabledCheckbox.setSelected(restaurant.aiFeaturesEnabled());
        aiProviderChoice.setValue(providerCodeToLabel(restaurant.aiProvider()));
        aiModelField.setText(restaurant.aiModel() == null ? "" : restaurant.aiModel());
        aiApiKeyField.setText("");
        clearApiKeyCheckbox.setSelected(false);
        aiApiKeyStatusLabel.setText(restaurant.aiApiKeyConfigured()
                ? "API key configured (leave blank to keep it, or check \"Clear saved API key\" to remove it)"
                : "No API key configured yet");
        aiMenuImportEnabledCheckbox.setSelected(restaurant.aiMenuImportEnabled());
        aiInsightsChatEnabledCheckbox.setSelected(restaurant.aiInsightsChatEnabled());
        aiReorderDraftsEnabledCheckbox.setSelected(restaurant.aiReorderDraftsEnabled());
        aiAnomalyFlaggingEnabledCheckbox.setSelected(restaurant.aiAnomalyFlaggingEnabled());
        aiMenuDescriptionsEnabledCheckbox.setSelected(restaurant.aiMenuDescriptionsEnabled());
        aiNightlySummaryEnabledCheckbox.setSelected(restaurant.aiNightlySummaryEnabled());
        aiReplenishmentNotesEnabledCheckbox.setSelected(restaurant.aiReplenishmentNotesEnabled());
        ocrUseAiVisionAssistCheckbox.setSelected(restaurant.ocrUseAiVisionAssist());
        biometricOverrideEnabledCheckbox.setSelected(restaurant.biometricOverrideEnabled());
        autoPurgeEnabledCheckbox.setSelected(restaurant.autoPurgeEnabled());
        dataRetentionDaysField.setText(String.valueOf(restaurant.dataRetentionDays()));
        dashboardViewModeChoice.setValue(restaurant.dashboardViewMode());
        kitchenServiceModeChoice.setValue(restaurant.kitchenServiceMode());
        showDiscountConfirmationCheckbox.setSelected(restaurant.showDiscountConfirmation());
        poApprovalRequiredCheckbox.setSelected(restaurant.poApprovalRequired());

        String enabled = restaurant.enabledPaymentMethods() == null ? "" : restaurant.enabledPaymentMethods();
        cashMethodCheckbox.setSelected(enabled.contains("CASH"));
        cardMethodCheckbox.setSelected(enabled.contains("CARD"));
        upiMethodCheckbox.setSelected(enabled.contains("UPI"));
        walletMethodCheckbox.setSelected(enabled.contains("WALLET"));
        otherMethodCheckbox.setSelected(enabled.contains("OTHER"));
        upiVpaField.setText(restaurant.upiVpaId() == null ? "" : restaurant.upiVpaId());
        upiPayeeNameField.setText(restaurant.upiPayeeName() == null ? "" : restaurant.upiPayeeName());
        cardPaymentEnabledCheckbox.setSelected(restaurant.cardPaymentEnabled());
        cardTerminalNoteField.setText(restaurant.cardTerminalNote() == null ? "" : restaurant.cardTerminalNote());
        cashDrawerEnabledCheckbox.setSelected(restaurant.cashDrawerEnabled());
        paperWidthField.setText(String.valueOf(restaurant.receiptPaperWidthChars() > 0 ? restaurant.receiptPaperWidthChars() : 40));
        if (restaurant.receiptPrinterName() != null && !restaurant.receiptPrinterName().isBlank()) {
            if (!printerNameChoice.getItems().contains(restaurant.receiptPrinterName())) {
                printerNameChoice.getItems().add(restaurant.receiptPrinterName());
            }
            printerNameChoice.setValue(restaurant.receiptPrinterName());
        }
    }

    private void save() {
        if (currentRestaurant == null) {
            return;
        }
        if (nameField.getText() == null || nameField.getText().isBlank()) {
            new Alert(Alert.AlertType.WARNING, "Restaurant Name can't be blank.").showAndWait();
            return;
        }
        BigDecimal serviceCharge;
        try {
            String raw = serviceChargeField.getText() == null ? "" : serviceChargeField.getText().trim();
            serviceCharge = new BigDecimal(raw.isEmpty() ? "0" : raw);
        } catch (NumberFormatException ex) {
            new Alert(Alert.AlertType.WARNING, "Service Charge % must be a number.").showAndWait();
            return;
        }
        Integer paperWidth;
        try {
            String raw = paperWidthField.getText() == null ? "" : paperWidthField.getText().trim();
            paperWidth = raw.isEmpty() ? 40 : Integer.parseInt(raw);
            if (paperWidth <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            new Alert(Alert.AlertType.WARNING, "Paper Width must be a positive whole number.").showAndWait();
            return;
        }
        Integer dataRetentionDays;
        try {
            String raw = dataRetentionDaysField.getText() == null ? "" : dataRetentionDaysField.getText().trim();
            dataRetentionDays = raw.isEmpty() ? 30 : Integer.parseInt(raw);
            if (dataRetentionDays <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            new Alert(Alert.AlertType.WARNING, "Data Retention (days) must be a positive whole number.").showAndWait();
            return;
        }
        Integer smtpPort;
        try {
            String raw = smtpPortField.getText() == null ? "" : smtpPortField.getText().trim();
            smtpPort = raw.isEmpty() ? 587 : Integer.parseInt(raw);
            if (smtpPort <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            new Alert(Alert.AlertType.WARNING, "SMTP Port must be a positive whole number.").showAndWait();
            return;
        }

        StringBuilder methods = new StringBuilder();
        if (cashMethodCheckbox.isSelected()) methods.append("CASH,");
        if (cardMethodCheckbox.isSelected()) methods.append("CARD,");
        if (upiMethodCheckbox.isSelected()) methods.append("UPI,");
        if (walletMethodCheckbox.isSelected()) methods.append("WALLET,");
        if (otherMethodCheckbox.isSelected()) methods.append("OTHER,");
        String enabledMethods = methods.length() == 0 ? "CASH" : methods.substring(0, methods.length() - 1);

        var request = new RestaurantDtos.UpdateRestaurantRequest(
                nameField.getText().trim(),
                currencyField.getText().trim(),
                null, // default timezone isn't surfaced on this screen - left unchanged
                blankToNull(gstinField.getText()),
                blankToNull(phoneField.getText()),
                serviceCharge,
                kitchenSyncCheckbox.isSelected(),
                blankToNull(receiptFooterField.getText()),
                zomatoCheckbox.isSelected(),
                swiggyCheckbox.isSelected(),
                enabledMethods,
                blankToNull(upiVpaField.getText()),
                blankToNull(upiPayeeNameField.getText()),
                cardPaymentEnabledCheckbox.isSelected(),
                blankToNull(cardTerminalNoteField.getText()),
                cashDrawerEnabledCheckbox.isSelected(),
                printerNameChoice.getValue(),
                paperWidth,
                autoPrintOnlineOrdersCheckbox.isSelected(),
                autoPrintReceiptOnPaymentCheckbox.isSelected(),
                deliveryBoyFeatureEnabledCheckbox.isSelected(),
                kotOptionalEnabledCheckbox.isSelected(),
                pendingLogoBase64,
                blankToNull(smtpHostField.getText()),
                smtpPort,
                blankToNull(smtpUsernameField.getText()),
                clearSmtpPasswordCheckbox.isSelected() ? "" : blankToNull(smtpPasswordField.getText()),
                blankToNull(smtpFromAddressField.getText()),
                smtpUseTlsCheckbox.isSelected(),
                aiFeaturesEnabledCheckbox.isSelected(),
                providerLabelToCode(aiProviderChoice.getValue()),
                clearApiKeyCheckbox.isSelected() ? "" : blankToNull(aiApiKeyField.getText()),
                blankToNull(aiModelField.getText()),
                aiMenuImportEnabledCheckbox.isSelected(),
                aiInsightsChatEnabledCheckbox.isSelected(),
                aiReorderDraftsEnabledCheckbox.isSelected(),
                aiAnomalyFlaggingEnabledCheckbox.isSelected(),
                aiMenuDescriptionsEnabledCheckbox.isSelected(),
                aiNightlySummaryEnabledCheckbox.isSelected(),
                dashboardViewModeChoice.getValue(),
                kitchenServiceModeChoice.getValue(),
                showDiscountConfirmationCheckbox.isSelected(),
                poApprovalRequiredCheckbox.isSelected(),
                aiReplenishmentNotesEnabledCheckbox.isSelected(),
                ocrUseAiVisionAssistCheckbox.isSelected(),
                biometricOverrideEnabledCheckbox.isSelected(),
                autoPurgeEnabledCheckbox.isSelected(),
                dataRetentionDays,
                currentRestaurant.version());

        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.put("/api/restaurant", request);
                RestaurantDtos.RestaurantDto updated = apiClient.convert(data, RestaurantDtos.RestaurantDto.class);
                Platform.runLater(() -> {
                    renderRestaurant(updated);
                    statusLabel.setText("Saved");
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                        reload();
                        new Alert(Alert.AlertType.WARNING,
                                "Settings were updated elsewhere - showing the latest version, please retry.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-settings-save");
        worker.setDaemon(true);
        worker.start();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String providerCodeToLabel(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "OPENAI" -> "OpenAI";
            case "ANTHROPIC" -> "Anthropic (Claude)";
            case "GEMINI" -> "Google Gemini";
            default -> null;
        };
    }

    private String providerLabelToCode(String label) {
        if (label == null) {
            return null;
        }
        return switch (label) {
            case "OpenAI" -> "OPENAI";
            case "Anthropic (Claude)" -> "ANTHROPIC";
            case "Google Gemini" -> "GEMINI";
            default -> null;
        };
    }

    public Parent view() {
        return root;
    }
}
