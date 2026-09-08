-- ChefPay Phase 3 schema additions (Kitchen Stations). Same portability caveat as V1/V2: written
-- against the JPA mapping but not run against a live PostgreSQL/MySQL instance in the environment
-- this was authored in - review before trusting ddl-auto=validate not to fail on a mismatch (see
-- README "Build status"). Permission/role rows (including the INVENTORY_VIEW/INVENTORY_MANAGE
-- codes added alongside this phase for later use) are seeded by the application itself
-- (DataSeeder), not by SQL migration, on every profile - see that class's Javadoc for why it
-- upserts idempotently on every startup rather than a one-time INSERT here.

CREATE TABLE kitchen_station (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    name           VARCHAR(100) NOT NULL,
    display_order  INT          NOT NULL DEFAULT 0,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_kitchen_station_name UNIQUE (name)
);

ALTER TABLE menu_item ADD COLUMN station_id CHAR(36);
ALTER TABLE menu_item ADD CONSTRAINT fk_menu_item_station FOREIGN KEY (station_id) REFERENCES kitchen_station (id);
