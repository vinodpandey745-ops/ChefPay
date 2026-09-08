-- Round 11: configurable "skip kitchen dispatch, bill directly" workflow.
ALTER TABLE restaurant ADD COLUMN kot_optional_enabled BOOLEAN NOT NULL DEFAULT FALSE;
