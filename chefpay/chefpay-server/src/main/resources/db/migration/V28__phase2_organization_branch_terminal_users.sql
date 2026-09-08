-- Phase 2: Organization -> Branch -> Terminal -> Users/Roles foundation. All additive - existing
-- rows keep working unmodified (see PHASE2_ORG_SUBSCRIPTION_DESIGN.md's Gap Analysis). SQLite
-- (default 'dev' profile) uses Hibernate ddl-auto instead, same as every prior migration - see
-- V1's header note. Permission/role seeding (new codes + the OWNER role) is NOT done here - it
-- goes through DataSeeder.java's existing additive-every-startup seeder, same as every permission
-- added since Phase 1, rather than raw SQL inserts.

-- Organization (Restaurant enriched) --------------------------------------------------------------
ALTER TABLE restaurant ADD COLUMN contact_email VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN address_line1 VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN address_line2 VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN city VARCHAR(100);
ALTER TABLE restaurant ADD COLUMN state VARCHAR(100);
ALTER TABLE restaurant ADD COLUMN postal_code VARCHAR(20);
ALTER TABLE restaurant ADD COLUMN country VARCHAR(100);
-- ACTIVE | SUSPENDED | CLOSED - see RestaurantStatus.java. Every existing row defaults to ACTIVE.
ALTER TABLE restaurant ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
-- gstin/support_phone already exist (Round 9/17) - the Tax/GST and contact-phone requirements are
-- already satisfied by existing columns, not repeated here.

-- Branch -------------------------------------------------------------------------------------------
ALTER TABLE branch ADD COLUMN branch_code VARCHAR(20);
ALTER TABLE branch ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
-- Plain unique index (not partial/filtered) for the same NULL-is-distinct-from-NULL reason
-- V26's terminal_code index already documents - any number of not-yet-backfilled (null)
-- branch_code rows are allowed pre-backfill; DataSeeder backfills every existing row on next boot
-- (see DataSeeder#ensurePhase2Backfill), after which every row has a real, unique code.
CREATE UNIQUE INDEX idx_branch_code ON branch(branch_code);

-- Terminal (Device) ---------------------------------------------------------------------------------
-- Sequence number scoped to the terminal's own branch - "Terminal 001", "002"... within that
-- branch, not globally. Nullable: a terminal with no branch yet (pre-existing, unassigned Device
-- rows) has no sequence either, same "optional until configured" pattern branch_id/terminal_code
-- already follow on this same table (V26).
ALTER TABLE device ADD COLUMN sequence_no INT;
CREATE UNIQUE INDEX idx_device_branch_sequence ON device(branch_id, sequence_no);

-- AppUser: deterministic login identity (Phase 2's fix for the duplicate-PIN ambiguity bug - see
-- Gap Analysis) -------------------------------------------------------------------------------------
ALTER TABLE app_user ADD COLUMN user_code VARCHAR(32);
CREATE UNIQUE INDEX idx_app_user_code ON app_user(user_code);

-- Per-terminal user restriction, same empty-means-unrestricted convention as the existing
-- app_user_branch table (Round 12) ------------------------------------------------------------------
CREATE TABLE app_user_terminal (
    app_user_id CHAR(36) NOT NULL,
    device_id   CHAR(36) NOT NULL,
    PRIMARY KEY (app_user_id, device_id),
    CONSTRAINT fk_aut_user   FOREIGN KEY (app_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_aut_device FOREIGN KEY (device_id)   REFERENCES device(id)
);
