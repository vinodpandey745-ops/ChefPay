-- Round 13: AI Backbone Addendum, Phase 1 (Foundational Trust) - Automated EOD Reconciliation,
-- Blind Cash Count, Aggregator Settlement (manual entry), Tier-1 Fraud/Loss-Prevention Rule
-- Engine + Anomaly review, and the AuditLog hash-chain columns. SQLite (default 'dev' profile)
-- uses Hibernate ddl-auto instead, same as every other migration in this project - see V1's
-- header note.

CREATE TABLE eod_session (
    id                        CHAR(36)      NOT NULL PRIMARY KEY,
    version                   BIGINT        NOT NULL,
    created_at                TIMESTAMP     NOT NULL,
    updated_at                TIMESTAMP     NOT NULL,
    business_date             DATE          NOT NULL UNIQUE,
    status                    VARCHAR(20)   NOT NULL,
    opening_float             DECIMAL(12,2) NOT NULL DEFAULT 0,
    started_by                CHAR(36),
    finalized_by              CHAR(36),
    finalized_at              TIMESTAMP,
    finalize_override_reason  VARCHAR(1000),
    finalized_with_override   BOOLEAN       NOT NULL DEFAULT FALSE,
    z_report_text             TEXT,
    z_report_pdf_base64       TEXT,
    gl_sync_status            VARCHAR(20)   NOT NULL DEFAULT 'NOT_CONFIGURED',
    gl_sync_message           VARCHAR(1000),
    CONSTRAINT fk_eod_started_by FOREIGN KEY (started_by) REFERENCES app_user (id),
    CONSTRAINT fk_eod_finalized_by FOREIGN KEY (finalized_by) REFERENCES app_user (id)
);
CREATE INDEX idx_eod_session_business_date ON eod_session (business_date);

CREATE TABLE channel_ingestion (
    id              CHAR(36)      NOT NULL PRIMARY KEY,
    version         BIGINT        NOT NULL,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    eod_session_id  CHAR(36)      NOT NULL,
    channel_type    VARCHAR(20)   NOT NULL,
    channel_name    VARCHAR(100)  NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    amount_reported DECIMAL(12,2),
    message         VARCHAR(1000),
    ingested_at     TIMESTAMP,
    CONSTRAINT fk_channel_ing_session FOREIGN KEY (eod_session_id) REFERENCES eod_session (id)
);
CREATE INDEX idx_channel_ingestion_session ON channel_ingestion (eod_session_id);

CREATE TABLE cash_count (
    id                          CHAR(36)      NOT NULL PRIMARY KEY,
    version                     BIGINT        NOT NULL,
    created_at                  TIMESTAMP     NOT NULL,
    updated_at                  TIMESTAMP     NOT NULL,
    eod_session_id              CHAR(36)      NOT NULL UNIQUE,
    denomination_breakdown_json TEXT          NOT NULL,
    physical_total              DECIMAL(12,2) NOT NULL,
    expected_total               DECIMAL(12,2) NOT NULL,
    variance                    DECIMAL(12,2) NOT NULL,
    status                      VARCHAR(25)   NOT NULL,
    counted_by                  CHAR(36),
    counted_at                  TIMESTAMP,
    override_by                 CHAR(36),
    override_reason             VARCHAR(1000),
    overridden_at                TIMESTAMP,
    CONSTRAINT fk_cash_count_session FOREIGN KEY (eod_session_id) REFERENCES eod_session (id),
    CONSTRAINT fk_cash_count_counted_by FOREIGN KEY (counted_by) REFERENCES app_user (id),
    CONSTRAINT fk_cash_count_override_by FOREIGN KEY (override_by) REFERENCES app_user (id)
);

CREATE TABLE aggregator_settlement (
    id                        CHAR(36)      NOT NULL PRIMARY KEY,
    version                   BIGINT        NOT NULL,
    created_at                TIMESTAMP     NOT NULL,
    updated_at                TIMESTAMP     NOT NULL,
    eod_session_id            CHAR(36)      NOT NULL,
    channel_ingestion_id      CHAR(36)      NOT NULL,
    aggregator_name           VARCHAR(100)  NOT NULL,
    pos_recorded_total        DECIMAL(12,2) NOT NULL,
    reported_settlement_total DECIMAL(12,2) NOT NULL,
    commission_amount         DECIMAL(12,2),
    variance_percent          DECIMAL(7,2),
    flagged                   BOOLEAN       NOT NULL DEFAULT FALSE,
    notes                     VARCHAR(1000),
    entered_by                CHAR(36),
    entered_at                TIMESTAMP,
    CONSTRAINT fk_agg_settle_session FOREIGN KEY (eod_session_id) REFERENCES eod_session (id),
    CONSTRAINT fk_agg_settle_channel FOREIGN KEY (channel_ingestion_id) REFERENCES channel_ingestion (id),
    CONSTRAINT fk_agg_settle_entered_by FOREIGN KEY (entered_by) REFERENCES app_user (id)
);
CREATE INDEX idx_agg_settlement_session ON aggregator_settlement (eod_session_id);

CREATE TABLE rule_config (
    id                       CHAR(36)      NOT NULL PRIMARY KEY,
    version                  BIGINT        NOT NULL,
    created_at               TIMESTAMP     NOT NULL,
    updated_at               TIMESTAMP     NOT NULL,
    rule_code                VARCHAR(40)   NOT NULL UNIQUE,
    threshold_value          DECIMAL(12,2) NOT NULL,
    secondary_threshold_value DECIMAL(12,2),
    window_minutes           INT,
    severity                 VARCHAR(20)   NOT NULL,
    enabled                  BOOLEAN       NOT NULL DEFAULT TRUE,
    description              VARCHAR(500)
);

CREATE TABLE anomaly (
    id                    CHAR(36)      NOT NULL PRIMARY KEY,
    version               BIGINT        NOT NULL,
    created_at            TIMESTAMP     NOT NULL,
    updated_at            TIMESTAMP     NOT NULL,
    business_date         DATE          NOT NULL,
    eod_session_id        CHAR(36),
    rule_code             VARCHAR(40)   NOT NULL,
    severity              VARCHAR(20)   NOT NULL,
    category              VARCHAR(255)  NOT NULL,
    description           VARCHAR(2000) NOT NULL,
    reference_entity_type VARCHAR(255),
    reference_entity_id   CHAR(36),
    reference_order_id    CHAR(36),
    amount_impact         DECIMAL(12,2),
    involved_user_id      CHAR(36),
    involved_device_id    CHAR(36),
    status                VARCHAR(15)   NOT NULL DEFAULT 'UNREVIEWED',
    detected_at           TIMESTAMP     NOT NULL,
    resolution_type       VARCHAR(20),
    escalation_reason_code VARCHAR(50),
    resolution_note       VARCHAR(2000),
    resolved_by           CHAR(36),
    resolved_at           TIMESTAMP,
    CONSTRAINT fk_anomaly_session FOREIGN KEY (eod_session_id) REFERENCES eod_session (id),
    CONSTRAINT fk_anomaly_involved_user FOREIGN KEY (involved_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_anomaly_resolved_by FOREIGN KEY (resolved_by) REFERENCES app_user (id)
);
CREATE INDEX idx_anomaly_business_date ON anomaly (business_date);
CREATE INDEX idx_anomaly_status ON anomaly (status);
CREATE INDEX idx_anomaly_session ON anomaly (eod_session_id);

CREATE TABLE audit_chain_state (
    id        VARCHAR(20) NOT NULL PRIMARY KEY,
    last_hash VARCHAR(64) NOT NULL,
    last_seq  BIGINT      NOT NULL
);
INSERT INTO audit_chain_state (id, last_hash, last_seq)
VALUES ('SINGLETON', '0000000000000000000000000000000000000000000000000000000000000000', 0);

-- AuditLog hash chain (F1.3). Existing rows keep NULL chain_seq/entry_hash/prev_hash - the chain
-- starts fresh from the first row written after this migration, see AuditLog#chainSeq's javadoc.
ALTER TABLE audit_log ADD COLUMN chain_seq BIGINT;
ALTER TABLE audit_log ADD COLUMN entry_hash VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN prev_hash VARCHAR(64);
CREATE INDEX idx_audit_log_chain_seq ON audit_log (chain_seq);

-- Restaurant: EOD defaults (F1.2/F1.7).
ALTER TABLE restaurant ADD COLUMN default_opening_float DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE restaurant ADD COLUMN cash_variance_threshold DECIMAL(12,2) NOT NULL DEFAULT 100.00;
ALTER TABLE restaurant ADD COLUMN eod_z_report_recipient_emails VARCHAR(1000);
