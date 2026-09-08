-- Bistrodesk Phase 8 (requirement #24/#25): reservation capacity checking and table-blocking/
-- overlap detection both need an end instant for a booking, and Reservation only ever stored a
-- start instant (reserved_for) - these two columns are what make an end instant computable.
-- Both additive/nullable-or-defaulted, matching every other Bistrodesk migration's convention.

-- Restaurant-wide default slot length (minutes) used whenever a reservation doesn't set its own
-- override below. 90 minutes (a common full-service turnaround) for every existing restaurant row.
ALTER TABLE restaurant ADD COLUMN default_reservation_duration_minutes INT NOT NULL DEFAULT 90;

-- Per-reservation override - nullable, meaning "use the restaurant default above". Existing rows
-- all get NULL (today's only behavior: the restaurant-wide default applies to every booking).
ALTER TABLE reservation ADD COLUMN duration_minutes INT;
