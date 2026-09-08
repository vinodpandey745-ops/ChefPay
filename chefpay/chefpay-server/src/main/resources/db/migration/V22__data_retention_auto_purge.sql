-- Final round (post-delivery follow-up): data-retention auto-purge, prompted by a real SQLite
-- "too many terms in compound SELECT" startup crash caused by the schema's own size (a SHAPE
-- problem, fixed separately in code via SqliteDataSourceConfig - not by this migration). Since a
-- SQLite deployment can also be an actual production database for a small restaurant (requirement
-- Section 5), the user additionally asked for the DATA (row count/file size) to be bounded over
-- time: DataRetentionService/DataRetentionScheduler purge old operational logs and old fully-paid,
-- EOD-finalized orders/payments past a configurable retention window. Off by default - nothing is
-- ever purged until an owner/admin opts in via Settings.
-- SQLite (default 'dev' profile) uses Hibernate ddl-auto instead, same as every other migration in
-- this project - see V1's header note.

ALTER TABLE restaurant ADD COLUMN auto_purge_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE restaurant ADD COLUMN data_retention_days INTEGER NOT NULL DEFAULT 30;
