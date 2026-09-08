-- ChefPay Phase 2 schema additions (Orders, Order Items, idempotency, number sequences).
-- Same portability caveat as V1: written against the JPA mapping but not run against a live
-- PostgreSQL/MySQL instance in the environment this was authored in - review before trusting
-- ddl-auto=validate not to fail on a mismatch (see README "Build status").

CREATE TABLE customer_order (
    id                      CHAR(36)      NOT NULL PRIMARY KEY,
    version                 BIGINT        NOT NULL,
    created_at              TIMESTAMP     NOT NULL,
    updated_at              TIMESTAMP     NOT NULL,
    order_number            VARCHAR(64)   NOT NULL,
    order_type              VARCHAR(32)   NOT NULL,
    table_id                CHAR(36),
    customer_name           VARCHAR(255),
    customer_phone          VARCHAR(32),
    waiter_id                CHAR(36),
    cashier_id              CHAR(36),
    status                  VARCHAR(32)   NOT NULL,
    payment_status          VARCHAR(32)   NOT NULL,
    priority                VARCHAR(16)   NOT NULL,
    subtotal                DECIMAL(12,2) NOT NULL,
    discount_amount         DECIMAL(12,2) NOT NULL,
    tax_amount               DECIMAL(12,2) NOT NULL,
    service_charge_amount   DECIMAL(12,2) NOT NULL,
    tip_amount               DECIMAL(12,2) NOT NULL,
    total_amount             DECIMAL(12,2) NOT NULL,
    notes                   VARCHAR(1000),
    sent_to_kitchen_at      TIMESTAMP,
    billed_at               TIMESTAMP,
    closed_at               TIMESTAMP,
    CONSTRAINT uq_order_number UNIQUE (order_number),
    CONSTRAINT fk_order_table FOREIGN KEY (table_id) REFERENCES restaurant_table (id),
    CONSTRAINT fk_order_waiter FOREIGN KEY (waiter_id) REFERENCES app_user (id),
    CONSTRAINT fk_order_cashier FOREIGN KEY (cashier_id) REFERENCES app_user (id)
);
CREATE INDEX idx_order_status ON customer_order (status);
CREATE INDEX idx_order_table ON customer_order (table_id);
CREATE INDEX idx_order_created ON customer_order (created_at);
CREATE INDEX idx_order_customer_phone ON customer_order (customer_phone);

CREATE TABLE order_item (
    id                     CHAR(36)      NOT NULL PRIMARY KEY,
    version                BIGINT        NOT NULL,
    created_at             TIMESTAMP     NOT NULL,
    updated_at             TIMESTAMP     NOT NULL,
    order_id               CHAR(36)      NOT NULL,
    menu_item_id           CHAR(36)      NOT NULL,
    quantity               DECIMAL(10,3) NOT NULL,
    unit_price_snapshot    DECIMAL(12,2) NOT NULL,
    status                 VARCHAR(32)   NOT NULL,
    priority               BOOLEAN       NOT NULL DEFAULT FALSE,
    special_instructions   VARCHAR(1000),
    modifiers_summary      VARCHAR(500),
    cancel_reason          VARCHAR(500),
    sent_at                TIMESTAMP,
    accepted_at            TIMESTAMP,
    started_at             TIMESTAMP,
    ready_at               TIMESTAMP,
    served_at              TIMESTAMP,
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
    CONSTRAINT fk_item_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_item (id)
);
CREATE INDEX idx_order_item_order ON order_item (order_id);
CREATE INDEX idx_order_item_status ON order_item (status);

CREATE TABLE idempotency_record (
    composite_key  VARCHAR(200) NOT NULL PRIMARY KEY,
    operation      VARCHAR(64)  NOT NULL,
    result_json    TEXT,
    created_at     TIMESTAMP    NOT NULL
);

CREATE TABLE number_sequence (
    series_key      VARCHAR(64) NOT NULL PRIMARY KEY,
    current_value   BIGINT      NOT NULL
);
