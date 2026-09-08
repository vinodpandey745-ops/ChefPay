-- ChefPay Phase 5 schema additions (Inventory - stock items + transaction ledger). Same portability
-- caveat as V1-V4: written against the JPA mapping but not run against a live PostgreSQL/MySQL
-- instance in the environment this was authored in - review before trusting ddl-auto=validate not
-- to fail on a mismatch (see README "Build status"). The AUDIT_VIEW permission added alongside this
-- phase is seeded by the application itself (DataSeeder), not by SQL migration - see that class's
-- Javadoc for why it upserts idempotently on every startup rather than a one-time INSERT here.

CREATE TABLE inventory_item (
    id                 CHAR(36)      NOT NULL PRIMARY KEY,
    version            BIGINT        NOT NULL,
    created_at         TIMESTAMP     NOT NULL,
    updated_at         TIMESTAMP     NOT NULL,
    name               VARCHAR(150)  NOT NULL,
    unit               VARCHAR(20)   NOT NULL,
    quantity_on_hand   DECIMAL(14,3) NOT NULL DEFAULT 0,
    reorder_threshold  DECIMAL(14,3),
    cost_per_unit      DECIMAL(12,2),
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_inventory_item_name UNIQUE (name)
);

CREATE TABLE inventory_transaction (
    id                  CHAR(36)      NOT NULL PRIMARY KEY,
    version             BIGINT        NOT NULL,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    item_id             CHAR(36)      NOT NULL,
    type                VARCHAR(20)   NOT NULL,
    quantity            DECIMAL(14,3) NOT NULL,
    resulting_quantity  DECIMAL(14,3) NOT NULL,
    reason              VARCHAR(500)  NOT NULL,
    recorded_by         CHAR(36)      NOT NULL,
    CONSTRAINT fk_inventory_transaction_item FOREIGN KEY (item_id) REFERENCES inventory_item (id),
    CONSTRAINT fk_inventory_transaction_recorded_by FOREIGN KEY (recorded_by) REFERENCES app_user (id)
);
CREATE INDEX idx_inventory_transaction_item ON inventory_transaction (item_id);
CREATE INDEX idx_inventory_transaction_created_at ON inventory_transaction (created_at);

CREATE INDEX idx_audit_log_entity_type ON audit_log (entity_type);
CREATE INDEX idx_audit_log_user_id ON audit_log (user_id);
