-- UI Modernization Phase 1 follow-up: centralized appearance/theme configuration for chefpay-web.
-- One singleton row (get-or-create on first read, same convention as the single `restaurant` row)
-- holding the whole theme as an opaque JSON blob - see ThemeSettings' javadoc for why this is a
-- blob column rather than one column per token.
-- SQLite (default 'dev' profile) uses Hibernate ddl-auto instead, same as every other migration in
-- this project - see V1's header note.

CREATE TABLE theme_settings (
    id          CHAR(36)  NOT NULL PRIMARY KEY,
    version     BIGINT    NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    theme_json  TEXT
);
