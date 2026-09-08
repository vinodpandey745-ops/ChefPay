-- OPTIONAL, MANUAL hardening for AI Backbone Addendum NFR-3 ("the application's database role
-- has no UPDATE/DELETE grant on audit_log - UPDATE and DELETE are revoked at the Postgres role
-- level, not just the application layer").
--
-- This is NOT a Flyway migration and is deliberately never applied automatically. Reasons:
--   1. It needs the actual database role name the Spring Boot application connects as
--      (CHEFPAY_DB_USER in application.yml's 'postgres' profile), which is operator/environment
--      specific and unknown to this codebase - a wrong/guessed name here would either silently
--      no-op or, worse, revoke a grant the app needs on a role that happens to share a name.
--   2. A failed/unexpected REVOKE at migration time would block application startup for every
--      user, on every deployment, for a hardening step that already has an application-level
--      backstop (AuditLog's @PreUpdate/@PreRemove guard - see that entity's javadoc) covering the
--      actual code path that matters. That backstop is automatic and ships with Round 13; this
--      script is the *additional* database-level guarantee NFR-3 asks for, applied once, by a
--      DBA, against a real, already-verified database/role.
--
-- Usage: replace CHEFPAY_APP_ROLE below with your actual application database role, then run this
-- once against your Postgres instance (as a superuser or the table owner).

-- Prevent UPDATE/DELETE at the table level regardless of which role attempts it (defense in
-- depth beyond the per-role REVOKE below - a role rename or a second application role would
-- otherwise bypass a REVOKE that only targets one named role).
CREATE OR REPLACE FUNCTION audit_log_block_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not permitted on this table', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_log_block_mutation ON audit_log;
CREATE TRIGGER trg_audit_log_block_mutation
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_block_mutation();

-- Belt-and-suspenders: also revoke the grants directly from the application's own role, per
-- NFR-3's literal wording. Replace CHEFPAY_APP_ROLE with your actual role name first.
-- REVOKE UPDATE, DELETE ON audit_log FROM CHEFPAY_APP_ROLE;
