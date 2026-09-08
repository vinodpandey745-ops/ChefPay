-- Bistrodesk branch-isolation release (requirement #4, user-confirmed decision: "give each branch
-- its own restaurant profile"). Restaurant stays the ~70-field install-wide settings singleton it
-- always was (SMTP, AI keys, retention, payment toggles, etc. - genuinely shared, untouched by this
-- migration) - only the four fields a receipt or a branch's own "restaurant profile" screen
-- actually shows move here, alongside the name/address/phone Branch already had.

ALTER TABLE branch ADD COLUMN gstin VARCHAR(255);
ALTER TABLE branch ADD COLUMN support_phone VARCHAR(255);
ALTER TABLE branch ADD COLUMN receipt_footer_text VARCHAR(1000);
ALTER TABLE branch ADD COLUMN logo_image_base64 TEXT;

-- One-time seed: every existing branch's null fields are copied from the single current Restaurant
-- row, so no branch starts blank - it can then diverge from every other branch independently going
-- forward (BranchController#update). Portable correlated-subquery form (see V35's identical
-- portability note) - works on both PostgreSQL and MySQL 5.7+/8+.
UPDATE branch
SET gstin = (SELECT r.gstin FROM restaurant r ORDER BY r.created_at ASC LIMIT 1)
WHERE gstin IS NULL;

UPDATE branch
SET support_phone = (SELECT r.support_phone FROM restaurant r ORDER BY r.created_at ASC LIMIT 1)
WHERE support_phone IS NULL;

UPDATE branch
SET receipt_footer_text = (SELECT r.receipt_footer_text FROM restaurant r ORDER BY r.created_at ASC LIMIT 1)
WHERE receipt_footer_text IS NULL;

UPDATE branch
SET logo_image_base64 = (SELECT r.logo_image_base64 FROM restaurant r ORDER BY r.created_at ASC LIMIT 1)
WHERE logo_image_base64 IS NULL;
