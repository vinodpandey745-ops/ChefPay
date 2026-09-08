-- Phase 5's Settings slice: a "require kitchen sync before Mark Order Served" toggle on the
-- restaurant profile (see Restaurant.requireKitchenSyncForServed's javadoc for why this exists).
-- Defaulting existing rows to TRUE matches the entity's @Builder.Default and the intended fix -
-- restaurants that want the old lenient behavior back can flip it off from Settings.
ALTER TABLE restaurant ADD COLUMN require_kitchen_sync_for_served BOOLEAN NOT NULL DEFAULT TRUE;
