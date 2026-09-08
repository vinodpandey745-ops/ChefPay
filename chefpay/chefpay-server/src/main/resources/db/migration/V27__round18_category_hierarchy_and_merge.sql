-- Round 18: optional one-level category hierarchy (e.g. "Main Course" > "Veg"/"Non-Veg"), so the
-- Menu Editor's delete/merge-category and AI Menu Import fixes have a real "organize instead of
-- duplicate" option, not just flat categories. SQLite (default 'dev' profile) uses Hibernate
-- ddl-auto instead, same as every other migration in this project - see V1's header note.

ALTER TABLE menu_category ADD COLUMN parent_category_id CHAR(36);
ALTER TABLE menu_category ADD CONSTRAINT fk_menu_category_parent
    FOREIGN KEY (parent_category_id) REFERENCES menu_category(id);
