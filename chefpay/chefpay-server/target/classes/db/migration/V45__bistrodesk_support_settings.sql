-- Bistrodesk follow-up requirement ("Help icon + Support & Policy Configuration"): the user-side
-- Help popup's configurable content - support phone, support email, Terms & Conditions, Privacy
-- Policy - as one singleton row (get-or-create on first read, same convention as `theme_settings`
-- - see SupportSettings' javadoc for why this is a dedicated table rather than reusing
-- theme_settings' opaque JSON blob: these four fields have a fixed, known shape, unlike the
-- ever-growing appearance config).
-- SQLite (default 'dev' profile) uses Hibernate ddl-auto instead, same as every other migration in
-- this project - see V1's header note.

CREATE TABLE support_settings (
    id                    CHAR(36)  NOT NULL PRIMARY KEY,
    version               BIGINT    NOT NULL,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    support_phone         VARCHAR(255),
    support_email         VARCHAR(255),
    terms_and_conditions  TEXT,
    privacy_policy        TEXT
);
