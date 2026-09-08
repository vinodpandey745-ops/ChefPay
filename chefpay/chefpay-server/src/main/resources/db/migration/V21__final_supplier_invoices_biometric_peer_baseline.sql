-- Final round: F2.3 (OCR-assisted supplier invoice intake), F4.5 (biometric manager auth flag),
-- F3.3 (peer-baseline anomaly scoring - reuses the existing rule_config/anomaly tables, no new
-- columns needed since it's just another FraudRuleCode value seeded by DataSeeder, not a DDL
-- change), and F3.2's status vocabulary change (documented below, no column change needed).
-- SQLite (default 'dev' profile) uses Hibernate ddl-auto instead, same as every other migration in
-- this project - see V1's header note.

CREATE TABLE supplier_invoice (
    id                    CHAR(36)      NOT NULL PRIMARY KEY,
    version               BIGINT        NOT NULL,
    created_at            TIMESTAMP     NOT NULL,
    updated_at            TIMESTAMP     NOT NULL,
    supplier_id           CHAR(36),
    raw_image_base64      TEXT,
    extracted_raw_text    TEXT,
    extraction_method     VARCHAR(20),
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING_REVIEW',
    notes                 VARCHAR(1000),
    confirmed_by          CHAR(36),
    confirmed_at          TIMESTAMP,
    CONSTRAINT fk_supplier_invoice_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id),
    CONSTRAINT fk_supplier_invoice_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES app_user (id)
);
CREATE INDEX idx_supplier_invoice_status ON supplier_invoice (status);

CREATE TABLE supplier_invoice_line (
    id                  CHAR(36)      NOT NULL PRIMARY KEY,
    version             BIGINT        NOT NULL,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    invoice_id          CHAR(36)      NOT NULL,
    inventory_item_id   CHAR(36),
    description         VARCHAR(300),
    quantity            DECIMAL(14,3),
    unit_cost           DECIMAL(12,2),
    raw_text            VARCHAR(500),
    CONSTRAINT fk_supplier_invoice_line_invoice FOREIGN KEY (invoice_id) REFERENCES supplier_invoice (id),
    CONSTRAINT fk_supplier_invoice_line_inventory_item FOREIGN KEY (inventory_item_id) REFERENCES inventory_item (id)
);
CREATE INDEX idx_supplier_invoice_line_invoice ON supplier_invoice_line (invoice_id);

-- Restaurant: final-round config flags (F2.3's OCR/AI-vision preference, F4.5's biometric master switch).
ALTER TABLE restaurant ADD COLUMN ocr_use_ai_vision_assist BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE restaurant ADD COLUMN biometric_override_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- F3.2 note (no DDL change): price_change_suggestion.status's allowed values changed from
-- PENDING/APPROVED/REJECTED to PENDING/APPLIED/DISMISSED (see PriceChangeSuggestionStatus's
-- javadoc - "Apply" now actually writes MenuItem.price instead of being a decision-only approval).
-- The column is already VARCHAR(15), which comfortably fits every old and new value, so no ALTER
-- is needed here. Any pre-existing row already in APPROVED/REJECTED from before this change would
-- fail to deserialize as the new enum - in practice this only matters for a restaurant that had
-- already used the old two-step approve/reject flow before upgrading, which no environment this
-- project has been deployed to has done (this is a from-scratch build, not a live upgrade), so no
-- data-migration UPDATE statement is included; a real production upgrade would want one.
