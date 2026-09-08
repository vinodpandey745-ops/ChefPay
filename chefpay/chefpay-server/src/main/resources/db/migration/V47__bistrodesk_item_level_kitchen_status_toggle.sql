-- POS patch: configurable "kitchen staff can update an individual item's own status" toggle.
-- Off by default - existing whole-ticket kitchen workflow is unchanged unless a restaurant
-- explicitly opts in (see Restaurant#itemLevelKitchenStatusEnabled's javadoc).
ALTER TABLE restaurant ADD COLUMN item_level_kitchen_status_enabled BOOLEAN NOT NULL DEFAULT FALSE;
