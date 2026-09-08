-- Bistrodesk follow-up enhancement ("Local Time Zone During Branch Creation"): each branch gets its
-- own IANA time zone id, used wherever that branch's date/time functionality is displayed or
-- processed (see ReportService#businessZone()), instead of relying solely on the single
-- install-wide Restaurant#defaultTimezone fallback.

ALTER TABLE branch ADD COLUMN timezone VARCHAR(64);

-- One-time seed: every existing branch's null timezone is copied from its own parent Restaurant's
-- defaultTimezone (joined via branch.restaurant_id, not a blind "first restaurant" pick - correct
-- even if this ever stops being a single-restaurant-per-install product), so no branch starts
-- blank. It can then diverge from every other branch independently going forward
-- (BranchController#update, PlatformOwnerController#createBranch).
UPDATE branch b
SET timezone = (SELECT r.default_timezone FROM restaurant r WHERE r.id = b.restaurant_id)
WHERE b.timezone IS NULL;

-- Final safety net: if a branch's restaurant row somehow had a null/blank defaultTimezone too
-- (shouldn't happen - the column is NOT NULL - but stay defensive rather than leave a branch with
-- no zone at all), fall back to the same "Asia/Kolkata" default Restaurant#defaultTimezone uses.
UPDATE branch
SET timezone = 'Asia/Kolkata'
WHERE timezone IS NULL OR timezone = '';
