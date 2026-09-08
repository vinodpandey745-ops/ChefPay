-- Round 9: Delivery Boy roster, assignable to orders, gated by a new restaurant-level feature
-- toggle (off by default). Same portability caveat as V1-V11: written against the JPA mapping
-- but not run against a live PostgreSQL/MySQL instance in the environment this was authored in.

CREATE TABLE delivery_boy (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    name           VARCHAR(255) NOT NULL,
    phone          VARCHAR(255),
    active         BOOLEAN      NOT NULL DEFAULT TRUE
);

ALTER TABLE customer_order ADD COLUMN delivery_boy_id CHAR(36) REFERENCES delivery_boy (id);
CREATE INDEX idx_customer_order_delivery_boy ON customer_order (delivery_boy_id);

ALTER TABLE restaurant ADD COLUMN delivery_boy_feature_enabled BOOLEAN NOT NULL DEFAULT FALSE;
