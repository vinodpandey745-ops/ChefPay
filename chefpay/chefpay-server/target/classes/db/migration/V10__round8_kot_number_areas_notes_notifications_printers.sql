-- Round 8: per-line KOT numbering, named seating Areas, reusable special-instruction presets
-- (SpecialNote), a system Notification inbox, and per-purpose Printer Profiles replacing the
-- single global receipt printer name. Same portability caveat as V1-V9: written against the JPA
-- mapping but not run against a live PostgreSQL/MySQL instance in the environment this was
-- authored in.

ALTER TABLE order_item ADD COLUMN kot_number BIGINT;

CREATE TABLE area (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    branch_id      CHAR(36)     NOT NULL,
    name           VARCHAR(255) NOT NULL,
    display_order  INT          NOT NULL DEFAULT 0,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_area_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);
CREATE INDEX idx_area_branch ON area (branch_id);

CREATE TABLE special_note (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    branch_id      CHAR(36)     NOT NULL,
    text           VARCHAR(255) NOT NULL,
    display_order  INT          NOT NULL DEFAULT 0,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_special_note_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);
CREATE INDEX idx_special_note_branch ON special_note (branch_id);

CREATE TABLE notification (
    id             CHAR(36)      NOT NULL PRIMARY KEY,
    version        BIGINT        NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    updated_at     TIMESTAMP     NOT NULL,
    branch_id      CHAR(36)      NOT NULL,
    category       VARCHAR(64)   NOT NULL,
    message        VARCHAR(1000) NOT NULL,
    reference_id   CHAR(36),
    is_read        BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_notification_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);
CREATE INDEX idx_notification_branch ON notification (branch_id);
CREATE INDEX idx_notification_created ON notification (created_at);
CREATE INDEX idx_notification_is_read ON notification (is_read);

CREATE TABLE printer_profile (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    branch_id      CHAR(36)     NOT NULL,
    name           VARCHAR(255) NOT NULL,
    printer_name   VARCHAR(255) NOT NULL,
    for_bill       BOOLEAN      NOT NULL DEFAULT FALSE,
    for_kot        BOOLEAN      NOT NULL DEFAULT FALSE,
    for_ebill      BOOLEAN      NOT NULL DEFAULT FALSE,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_printer_profile_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);
CREATE INDEX idx_printer_profile_branch ON printer_profile (branch_id);
