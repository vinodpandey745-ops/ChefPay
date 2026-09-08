-- ChefPay Phase 1 schema (Restaurant/Branch/Floor/Table, RBAC, Menu, Device, AuditLog).
-- Targets PostgreSQL and MySQL (spring.profiles postgres / mysql - see application.yml).
-- SQLite (default 'dev' profile) uses Hibernate ddl-auto instead; this file is not applied there.
--
-- NOTE: written to be portable, generic SQL and cross-checked against the JPA mapping in
-- chefpay-core, but NOT executed against a live PostgreSQL/MySQL instance in this environment -
-- only the SQLite/Hibernate path has been runtime-verified. Review before production use, and
-- run `mvn -Pverify-migration` (or simply boot once against a disposable database) as a first step.
--
-- UUID columns are stored as CHAR(36) uniformly (see BaseEntity's @JdbcTypeCode(SqlTypes.CHAR))
-- so the exact same DDL works whether Hibernate is validating against Postgres or MySQL.

CREATE TABLE restaurant (
    id                CHAR(36)     NOT NULL PRIMARY KEY,
    version           BIGINT       NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    name              VARCHAR(255) NOT NULL,
    currency_symbol   VARCHAR(8)   NOT NULL,
    default_timezone  VARCHAR(64)  NOT NULL,
    gstin             VARCHAR(32),
    support_phone     VARCHAR(32)
);

CREATE TABLE branch (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    restaurant_id  CHAR(36)     NOT NULL,
    name           VARCHAR(255) NOT NULL,
    address        VARCHAR(500),
    phone          VARCHAR(32),
    CONSTRAINT fk_branch_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id)
);

CREATE TABLE floor (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    branch_id      CHAR(36)     NOT NULL,
    name           VARCHAR(255) NOT NULL,
    display_order  INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_floor_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);

CREATE TABLE restaurant_table (
    id                CHAR(36)     NOT NULL PRIMARY KEY,
    version           BIGINT       NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    floor_id          CHAR(36)     NOT NULL,
    name              VARCHAR(64)  NOT NULL,
    seating_capacity  INT          NOT NULL,
    section           VARCHAR(64),
    status            VARCHAR(32)  NOT NULL,
    grid_row          INT,
    grid_column       INT,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_table_floor FOREIGN KEY (floor_id) REFERENCES floor (id)
);
CREATE INDEX idx_table_status ON restaurant_table (status);
CREATE INDEX idx_table_floor ON restaurant_table (floor_id);

CREATE TABLE permission (
    id           CHAR(36)     NOT NULL PRIMARY KEY,
    version      BIGINT       NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    description  VARCHAR(255),
    CONSTRAINT uq_permission_code UNIQUE (code)
);

CREATE TABLE app_role (
    id           CHAR(36)     NOT NULL PRIMARY KEY,
    version      BIGINT       NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(255),
    CONSTRAINT uq_role_name UNIQUE (name)
);

CREATE TABLE role_permission (
    role_id        CHAR(36) NOT NULL,
    permission_id  CHAR(36) NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES app_role (id),
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
);

CREATE TABLE app_user (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    username       VARCHAR(64)  NOT NULL,
    display_name   VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    pin_hash       VARCHAR(255),
    role_id        CHAR(36)     NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_user_username UNIQUE (username),
    CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES app_role (id)
);

CREATE TABLE device (
    id            CHAR(36)     NOT NULL PRIMARY KEY,
    version       BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    name          VARCHAR(255) NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    last_user_id  CHAR(36),
    last_seen_at  TIMESTAMP,
    CONSTRAINT fk_device_user FOREIGN KEY (last_user_id) REFERENCES app_user (id)
);

CREATE TABLE menu_category (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    name           VARCHAR(255) NOT NULL,
    display_order  INT          NOT NULL DEFAULT 0,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_category_name UNIQUE (name)
);

CREATE TABLE menu_item (
    id            CHAR(36)      NOT NULL PRIMARY KEY,
    version       BIGINT        NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL,
    category_id   CHAR(36)      NOT NULL,
    name          VARCHAR(255)  NOT NULL,
    sku           VARCHAR(64),
    plu           VARCHAR(64),
    description   VARCHAR(1000),
    price         DECIMAL(12,2) NOT NULL,
    tax_code      VARCHAR(32),
    vegetarian    BOOLEAN       NOT NULL DEFAULT TRUE,
    available     BOOLEAN       NOT NULL DEFAULT TRUE,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    image_path    VARCHAR(500),
    barcode       VARCHAR(64),
    CONSTRAINT fk_item_category FOREIGN KEY (category_id) REFERENCES menu_category (id)
);
CREATE INDEX idx_item_category ON menu_item (category_id);

CREATE TABLE audit_log (
    id              CHAR(36)     NOT NULL PRIMARY KEY,
    user_id         CHAR(36),
    device_id       CHAR(36),
    entity_type     VARCHAR(128) NOT NULL,
    entity_id       CHAR(36),
    action          VARCHAR(128) NOT NULL,
    old_value       TEXT,
    new_value       TEXT,
    reason          VARCHAR(500),
    timestamp       TIMESTAMP    NOT NULL,
    correlation_id  VARCHAR(64)
);
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_timestamp ON audit_log (timestamp);
