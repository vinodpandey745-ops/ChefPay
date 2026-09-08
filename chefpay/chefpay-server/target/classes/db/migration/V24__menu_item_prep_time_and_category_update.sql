-- Web Modernization Round 15: Menu Editor full-CRUD pass.
-- Adds an optional prep-time estimate (minutes) to menu_item, shown in the new chefpay-web Menu
-- Editor page. Nothing in the kitchen/order flow reads this column - display-only, matching the
-- entity field's own javadoc. No schema change was needed for the new PATCH /api/menu/categories/{id}
-- endpoint (rename/active-toggle only, uses the existing menu_category columns) or for exposing
-- menu_item.barcode via the API (that column has existed since Phase 3, just wasn't in any DTO yet).
-- SQLite (default 'dev' profile) uses Hibernate ddl-auto instead, same as every other migration in
-- this project - see V1's header note.

ALTER TABLE menu_item ADD COLUMN prep_time_minutes INTEGER;
