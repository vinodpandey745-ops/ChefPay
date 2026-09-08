-- "Full-fledged POS" round: restaurant-level receipt/online-order config (see Restaurant's javadoc
-- for exactly what these three fields do and, just as importantly, what they deliberately don't -
-- no live Zomato/Swiggy connection, no receipt logo, no rounding rule) plus the new Customer guest
-- directory (see Customer's javadoc for why this is a standalone table with no FK from `order`
-- yet). CUSTOMER_VIEW/CUSTOMER_MANAGE permissions are seeded by DataSeeder at startup, same
-- idempotent-upsert pattern V5's comment already explains for AUDIT_VIEW - not inserted here.

ALTER TABLE restaurant ADD COLUMN receipt_footer_text VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN online_order_zomato_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE restaurant ADD COLUMN online_order_swiggy_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE customer (
    id            CHAR(36)      NOT NULL PRIMARY KEY,
    version       BIGINT        NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL,
    name          VARCHAR(150)  NOT NULL,
    phone         VARCHAR(30),
    email         VARCHAR(150),
    notes         VARCHAR(1000),
    visit_count   INT           NOT NULL DEFAULT 0,
    total_spend   DECIMAL(14,2) NOT NULL DEFAULT 0,
    last_visit_at TIMESTAMP
);
CREATE INDEX idx_customer_phone ON customer (phone);
CREATE INDEX idx_customer_name ON customer (name);
