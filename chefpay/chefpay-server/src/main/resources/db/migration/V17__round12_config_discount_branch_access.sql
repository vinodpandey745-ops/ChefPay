-- Round 12: dashboard/kitchen/billing configuration, discount preset enhancements (max cap +
-- category scoping), and per-user branch access (assignable branches + default branch).

ALTER TABLE restaurant ADD COLUMN dashboard_view_mode VARCHAR(20) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE restaurant ADD COLUMN kitchen_service_mode VARCHAR(20) NOT NULL DEFAULT 'DETAILED';
ALTER TABLE restaurant ADD COLUMN show_discount_confirmation BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE restaurant ADD COLUMN po_approval_required BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE restaurant ADD COLUMN ai_replenishment_notes_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE discount ADD COLUMN max_discount_amount DECIMAL(12,2);
ALTER TABLE discount ADD COLUMN applicable_category_id CHAR(36);
ALTER TABLE discount ADD CONSTRAINT fk_discount_applicable_category
    FOREIGN KEY (applicable_category_id) REFERENCES menu_category(id);

-- Per-user branch access: which branches a user may work at, plus a default. Null default_branch_id
-- is fine (and typical for a single-branch restaurant) - ShellView only shows a branch-selection
-- screen at all once a user has 2+ assigned branches (see AppUser/Branch access model, section 3).
ALTER TABLE app_user ADD COLUMN default_branch_id CHAR(36);
ALTER TABLE app_user ADD CONSTRAINT fk_app_user_default_branch
    FOREIGN KEY (default_branch_id) REFERENCES branch(id);

CREATE TABLE app_user_branch (
    app_user_id CHAR(36) NOT NULL,
    branch_id CHAR(36) NOT NULL,
    PRIMARY KEY (app_user_id, branch_id),
    CONSTRAINT fk_aub_user FOREIGN KEY (app_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_aub_branch FOREIGN KEY (branch_id) REFERENCES branch(id)
);
CREATE INDEX idx_app_user_branch_branch ON app_user_branch(branch_id);
