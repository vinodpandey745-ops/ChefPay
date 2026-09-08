-- Round 11: "Auto-print receipt on payment" - Settings toggle so a fully-paid bill prints straight
-- to the configured receipt printer (Restaurant.receiptPrinterName) instead of requiring a manual
-- "View Receipt" click, mirroring auto_print_online_orders' existing silent-print pattern.
ALTER TABLE restaurant ADD COLUMN auto_print_receipt_on_payment BOOLEAN NOT NULL DEFAULT FALSE;
