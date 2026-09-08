-- Round 12 §12-§26: Supplier, Purchase Order (+ items + share log). SQLite (default 'dev'
-- profile) uses Hibernate ddl-auto instead, same as every other migration in this project - see
-- V1's header note.

CREATE TABLE supplier (
    id              CHAR(36)      NOT NULL PRIMARY KEY,
    version         BIGINT        NOT NULL,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    name            VARCHAR(255)  NOT NULL,
    contact_person  VARCHAR(255),
    phone           VARCHAR(32),
    email           VARCHAR(255),
    address         VARCHAR(500),
    notes           VARCHAR(1000),
    active          BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE purchase_order (
    id                 CHAR(36)      NOT NULL PRIMARY KEY,
    version            BIGINT        NOT NULL,
    created_at         TIMESTAMP     NOT NULL,
    updated_at         TIMESTAMP     NOT NULL,
    po_number          VARCHAR(64)   NOT NULL UNIQUE,
    branch_id          CHAR(36)      NOT NULL,
    supplier_id        CHAR(36)      NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    created_by         CHAR(36)      NOT NULL,
    approved_by        CHAR(36),
    approved_at        TIMESTAMP,
    rejected_by        CHAR(36),
    rejected_at        TIMESTAMP,
    rejection_reason   VARCHAR(1000),
    submitted_at       TIMESTAMP,
    closed_at          TIMESTAMP,
    notes              VARCHAR(1000),
    CONSTRAINT fk_po_branch FOREIGN KEY (branch_id) REFERENCES branch (id),
    CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id),
    CONSTRAINT fk_po_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_po_approved_by FOREIGN KEY (approved_by) REFERENCES app_user (id),
    CONSTRAINT fk_po_rejected_by FOREIGN KEY (rejected_by) REFERENCES app_user (id)
);
CREATE INDEX idx_purchase_order_branch ON purchase_order (branch_id);
CREATE INDEX idx_purchase_order_status ON purchase_order (status);

CREATE TABLE purchase_order_item (
    id                  CHAR(36)      NOT NULL PRIMARY KEY,
    version             BIGINT        NOT NULL,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    purchase_order_id   CHAR(36)      NOT NULL,
    inventory_item_id   CHAR(36)      NOT NULL,
    ordered_quantity    DECIMAL(14,3) NOT NULL,
    unit_price          DECIMAL(12,2) NOT NULL,
    received_quantity   DECIMAL(14,3) NOT NULL DEFAULT 0,
    accepted_quantity   DECIMAL(14,3) NOT NULL DEFAULT 0,
    damaged_quantity    DECIMAL(14,3) NOT NULL DEFAULT 0,
    rejected_quantity   DECIMAL(14,3) NOT NULL DEFAULT 0,
    receiving_notes     VARCHAR(500),
    CONSTRAINT fk_poi_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    CONSTRAINT fk_poi_inventory_item FOREIGN KEY (inventory_item_id) REFERENCES inventory_item (id)
);
CREATE INDEX idx_po_item_purchase_order ON purchase_order_item (purchase_order_id);
CREATE INDEX idx_po_item_inventory_item ON purchase_order_item (inventory_item_id);

CREATE TABLE purchase_order_share_log (
    id                 CHAR(36)      NOT NULL PRIMARY KEY,
    version            BIGINT        NOT NULL,
    created_at         TIMESTAMP     NOT NULL,
    updated_at         TIMESTAMP     NOT NULL,
    purchase_order_id  CHAR(36)      NOT NULL,
    method             VARCHAR(20)   NOT NULL,
    recipient          VARCHAR(255),
    sent_by            CHAR(36)      NOT NULL,
    sent_at            TIMESTAMP     NOT NULL,
    status             VARCHAR(500)  NOT NULL,
    CONSTRAINT fk_po_share_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    CONSTRAINT fk_po_share_sent_by FOREIGN KEY (sent_by) REFERENCES app_user (id)
);
CREATE INDEX idx_po_share_purchase_order ON purchase_order_share_log (purchase_order_id);
