-- Round 17: Organization/Branch/Terminal identity model (identity-fields-only scope - not a full
-- multi-tenant rewrite) + offline-sync/backup groundwork. SQLite (default 'dev' profile) uses
-- Hibernate ddl-auto instead, same as every other migration in this project - see V1's header note.

ALTER TABLE restaurant ADD COLUMN organization_id VARCHAR(64);
ALTER TABLE restaurant ADD COLUMN organization_name VARCHAR(255);

-- Device doubles as this model's "Terminal" (see Device.java's Round 17 javadoc) - a registered
-- client instance already IS a terminal, so it gets the new identity/soft-disable columns rather
-- than a parallel entity.
ALTER TABLE device ADD COLUMN branch_id CHAR(36);
ALTER TABLE device ADD COLUMN terminal_code VARCHAR(32);
ALTER TABLE device ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE device ADD CONSTRAINT fk_device_branch FOREIGN KEY (branch_id) REFERENCES branch(id);
-- Plain unique index, not a partial/filtered one: Postgres, MySQL, and SQLite all treat NULL as
-- distinct-from-NULL in a unique index, so any number of not-yet-assigned (null) terminal_code
-- rows are already allowed without a WHERE clause (which MySQL doesn't support anyway).
CREATE UNIQUE INDEX idx_device_terminal_code ON device(terminal_code);
