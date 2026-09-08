-- ChefPay Round 7: direct-sale (skip-kitchen) menu items, half/full portion pricing, and a
-- configurable restaurant logo. Same portability caveat as V1-V8: written against the JPA mapping
-- but not run against a live PostgreSQL/MySQL instance in the environment this was authored in.

ALTER TABLE menu_item ADD COLUMN direct_sale BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE menu_item ADD COLUMN half_price DECIMAL(12,2);

ALTER TABLE restaurant ADD COLUMN logo_image_base64 TEXT;
