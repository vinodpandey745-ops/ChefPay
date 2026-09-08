-- Round 6: payment method config (UPI VPA, card terminal note, which methods are accepted),
-- receipt/KOT printer config (default printer name + paper width), cash drawer toggle, and the
-- online-order auto-print toggle. See Restaurant.java's javadoc on each field for exactly what it
-- does and, importantly, what it deliberately doesn't (no live payment-gateway/card-network
-- integration, no automatic UPI payment confirmation - see UpiQrGenerator's javadoc).

ALTER TABLE restaurant ADD COLUMN enabled_payment_methods VARCHAR(100) NOT NULL DEFAULT 'CASH,CARD,UPI';
ALTER TABLE restaurant ADD COLUMN upi_vpa_id VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN upi_payee_name VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN card_payment_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE restaurant ADD COLUMN card_terminal_note VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN cash_drawer_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE restaurant ADD COLUMN receipt_printer_name VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN receipt_paper_width_chars INT NOT NULL DEFAULT 40;
ALTER TABLE restaurant ADD COLUMN auto_print_online_orders BOOLEAN NOT NULL DEFAULT FALSE;
