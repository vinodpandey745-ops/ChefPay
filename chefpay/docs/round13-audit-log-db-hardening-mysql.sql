-- OPTIONAL, MANUAL hardening for AI Backbone Addendum NFR-3 - MySQL equivalent of
-- round13-audit-log-db-hardening-postgres.sql. See that file's header for why this is a manual
-- DBA script rather than an automatic Flyway migration (same reasoning applies here: it needs
-- your real application DB user, and MySQL's CREATE TRIGGER/REVOKE syntax differs enough from
-- Postgres's that a single cross-dialect Flyway migration file covering both engines is not a
-- safe thing to guess at without a real MySQL instance to verify against).
--
-- Usage: replace 'chefpay'@'%' below with your actual application DB user@host, then run this
-- once against your MySQL instance (as a user with TRIGGER/GRANT privileges).

DELIMITER $$

DROP TRIGGER IF EXISTS trg_audit_log_block_update$$
CREATE TRIGGER trg_audit_log_block_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only: UPDATE is not permitted on this table';
END$$

DROP TRIGGER IF EXISTS trg_audit_log_block_delete$$
CREATE TRIGGER trg_audit_log_block_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only: DELETE is not permitted on this table';
END$$

DELIMITER ;

-- Belt-and-suspenders: also revoke the grants directly from the application's own DB user, per
-- NFR-3's literal wording. Replace 'chefpay'@'%' with your actual user/host first.
-- REVOKE UPDATE, DELETE ON chefpay.audit_log FROM 'chefpay'@'%';
