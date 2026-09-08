-- Round 14: AI Backbone Addendum Phases 2-4 - Recipe/BOM (F2.1), Advisory Dynamic Pricing (F3.2),
-- and Omnichannel Critical Alerting (F4.2), plus small additive columns for F3.1 (preferred
-- supplier), F4.4 (CCTV footage link), and the new Restaurant-level config flags this round adds.
-- SQLite (default 'dev' profile) uses Hibernate ddl-auto instead, same as every other migration in
-- this project - see V1's header note.

CREATE TABLE recipe (
    id                  CHAR(36)      NOT NULL PRIMARY KEY,
    version             BIGINT        NOT NULL,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    menu_item_id        CHAR(36)      NOT NULL UNIQUE,
    servings_per_batch  INT           NOT NULL DEFAULT 1,
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    notes               VARCHAR(1000),
    CONSTRAINT fk_recipe_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_item (id)
);

CREATE TABLE recipe_line (
    id                  CHAR(36)      NOT NULL PRIMARY KEY,
    version             BIGINT        NOT NULL,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    recipe_id           CHAR(36)      NOT NULL,
    inventory_item_id   CHAR(36)      NOT NULL,
    quantity_per_batch  DECIMAL(14,3) NOT NULL,
    CONSTRAINT fk_recipe_line_recipe FOREIGN KEY (recipe_id) REFERENCES recipe (id),
    CONSTRAINT fk_recipe_line_inventory_item FOREIGN KEY (inventory_item_id) REFERENCES inventory_item (id)
);
CREATE INDEX idx_recipe_line_recipe ON recipe_line (recipe_id);

CREATE TABLE price_change_suggestion (
    id                        CHAR(36)      NOT NULL PRIMARY KEY,
    version                   BIGINT        NOT NULL,
    created_at                TIMESTAMP     NOT NULL,
    updated_at                TIMESTAMP     NOT NULL,
    menu_item_id              CHAR(36)      NOT NULL,
    current_price             DECIMAL(12,2) NOT NULL,
    current_recipe_cost       DECIMAL(12,2) NOT NULL,
    current_margin_percent    DECIMAL(5,2)  NOT NULL,
    suggested_price           DECIMAL(12,2) NOT NULL,
    projected_margin_percent  DECIMAL(5,2)  NOT NULL,
    reason                    VARCHAR(500)  NOT NULL,
    status                    VARCHAR(15)   NOT NULL DEFAULT 'PENDING',
    detected_at               TIMESTAMP     NOT NULL,
    decided_by                CHAR(36),
    decided_at                TIMESTAMP,
    decision_note             VARCHAR(1000),
    CONSTRAINT fk_price_suggestion_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_item (id),
    CONSTRAINT fk_price_suggestion_decided_by FOREIGN KEY (decided_by) REFERENCES app_user (id)
);
CREATE INDEX idx_price_suggestion_status ON price_change_suggestion (status);
CREATE INDEX idx_price_suggestion_menu_item ON price_change_suggestion (menu_item_id);

CREATE TABLE notification_log (
    id            CHAR(36)      NOT NULL PRIMARY KEY,
    version       BIGINT        NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL,
    anomaly_id    CHAR(36),
    category      VARCHAR(40)   NOT NULL,
    channel       VARCHAR(15)   NOT NULL,
    recipient     VARCHAR(255),
    subject       VARCHAR(200)  NOT NULL,
    message       VARCHAR(2000) NOT NULL,
    status        VARCHAR(25)   NOT NULL,
    error_message VARCHAR(1000),
    escalation    BOOLEAN       NOT NULL DEFAULT FALSE,
    attempted_at  TIMESTAMP     NOT NULL,
    CONSTRAINT fk_notification_log_anomaly FOREIGN KEY (anomaly_id) REFERENCES anomaly (id)
);
CREATE INDEX idx_notification_log_anomaly ON notification_log (anomaly_id);

-- F4.4: best-effort CCTV timestamp link on an anomaly - see Anomaly#cctvFootageUrl's javadoc.
ALTER TABLE anomaly ADD COLUMN cctv_footage_url VARCHAR(500);

-- F3.1: per-item preferred supplier, used by AutoReplenishmentScheduler to group auto-generated
-- draft POs - see InventoryItem#preferredSupplier's javadoc.
ALTER TABLE inventory_item ADD COLUMN preferred_supplier_id CHAR(36);
ALTER TABLE inventory_item ADD CONSTRAINT fk_inventory_item_preferred_supplier FOREIGN KEY (preferred_supplier_id) REFERENCES supplier (id);

-- Restaurant: Round 14 config flags (F3.1/F3.2/F4.1/F4.2).
ALTER TABLE restaurant ADD COLUMN margin_erosion_threshold_percent DECIMAL(5,2) NOT NULL DEFAULT 15.00;
ALTER TABLE restaurant ADD COLUMN auto_po_from_suggestions_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE restaurant ADD COLUMN critical_alert_escalation_minutes INT NOT NULL DEFAULT 30;
ALTER TABLE restaurant ADD COLUMN critical_alert_recipient_emails VARCHAR(1000);
ALTER TABLE restaurant ADD COLUMN nl_assistant_write_commands_enabled BOOLEAN NOT NULL DEFAULT FALSE;
