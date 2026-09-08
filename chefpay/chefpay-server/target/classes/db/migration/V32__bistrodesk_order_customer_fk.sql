-- Bistrodesk Phase 1: real Order -> Customer FK (requirement #2's prerequisite). Customer.java's
-- own javadoc named this exact column as the natural next step once the standalone Customer
-- directory existed ("needs a real Order.customerId FK to do reliably... left for that follow-up").
-- Nullable and additive only - customer_order.customer_name/customer_phone are untouched, so every
-- existing order (and every order screen that doesn't yet populate this new column) keeps working
-- exactly as before.

ALTER TABLE customer_order ADD COLUMN customer_id CHAR(36);

ALTER TABLE customer_order ADD CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES customer(id);
