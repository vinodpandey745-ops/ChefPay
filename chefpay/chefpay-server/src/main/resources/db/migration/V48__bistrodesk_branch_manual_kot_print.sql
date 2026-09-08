-- POS patch: configurable, per-branch "print KOT manually without sending to kitchen" workflow.
-- Off by default - every existing branch keeps today's normal Send to Kitchen / KOT behavior
-- unless explicitly opted in (see Branch#manualKotPrintEnabled's javadoc). Deliberately per-branch,
-- not on the install-wide Restaurant row, so enabling it for one branch never affects another.
ALTER TABLE branch ADD COLUMN manual_kot_print_enabled BOOLEAN NOT NULL DEFAULT FALSE;
