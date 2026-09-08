-- Round 11: SMTP config for emailed receipts (Restaurant.smtp*) - null/blank host means the
-- feature isn't configured for this restaurant, mirroring receipt_printer_name's own convention.
ALTER TABLE restaurant ADD COLUMN smtp_host VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN smtp_port INTEGER NOT NULL DEFAULT 587;
ALTER TABLE restaurant ADD COLUMN smtp_username VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN smtp_password VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN smtp_from_address VARCHAR(255);
ALTER TABLE restaurant ADD COLUMN smtp_use_tls BOOLEAN NOT NULL DEFAULT TRUE;
