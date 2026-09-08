-- ChefPay Phase 4 schema additions (Tax, Discount, Payments, Cash Management). Same portability
-- caveat as V1-V3: written against the JPA mapping but not run against a live PostgreSQL/MySQL
-- instance in the environment this was authored in - review before trusting ddl-auto=validate not
-- to fail on a mismatch (see README "Build status").

ALTER TABLE restaurant ADD COLUMN service_charge_percent DECIMAL(5,2) NOT NULL DEFAULT 0;

ALTER TABLE customer_order ADD COLUMN discount_reason VARCHAR(500);
ALTER TABLE customer_order ADD COLUMN paid_at TIMESTAMP;

CREATE TABLE tax_rate (
    id             CHAR(36)      NOT NULL PRIMARY KEY,
    version        BIGINT        NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    updated_at     TIMESTAMP     NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    code           VARCHAR(50)   NOT NULL,
    rate_percent   DECIMAL(5,2)  NOT NULL,
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    default_rate   BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_tax_rate_code UNIQUE (code)
);

CREATE TABLE discount (
    id             CHAR(36)      NOT NULL PRIMARY KEY,
    version        BIGINT        NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    updated_at     TIMESTAMP     NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    type           VARCHAR(20)   NOT NULL,
    value          DECIMAL(12,2) NOT NULL,
    active         BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE payment (
    id                CHAR(36)      NOT NULL PRIMARY KEY,
    version           BIGINT        NOT NULL,
    created_at        TIMESTAMP     NOT NULL,
    updated_at        TIMESTAMP     NOT NULL,
    order_id          CHAR(36)      NOT NULL,
    method            VARCHAR(20)   NOT NULL,
    amount            DECIMAL(12,2) NOT NULL,
    tendered_amount   DECIMAL(12,2),
    change_amount     DECIMAL(12,2),
    reference_number  VARCHAR(100),
    receipt_number    VARCHAR(64)   NOT NULL,
    received_by       CHAR(36)      NOT NULL,
    received_at       TIMESTAMP     NOT NULL,
    voided            BOOLEAN       NOT NULL DEFAULT FALSE,
    void_reason       VARCHAR(500),
    CONSTRAINT uq_payment_receipt_number UNIQUE (receipt_number),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
    CONSTRAINT fk_payment_received_by FOREIGN KEY (received_by) REFERENCES app_user (id)
);
CREATE INDEX idx_payment_order ON payment (order_id);
CREATE INDEX idx_payment_received_at ON payment (received_at);

CREATE TABLE cash_movement (
    id             CHAR(36)      NOT NULL PRIMARY KEY,
    version        BIGINT        NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    updated_at     TIMESTAMP     NOT NULL,
    type           VARCHAR(20)   NOT NULL,
    amount         DECIMAL(12,2) NOT NULL,
    reason         VARCHAR(500)  NOT NULL,
    recorded_by    CHAR(36)      NOT NULL,
    CONSTRAINT fk_cash_movement_recorded_by FOREIGN KEY (recorded_by) REFERENCES app_user (id)
);
CREATE INDEX idx_cash_movement_created_at ON cash_movement (created_at);
