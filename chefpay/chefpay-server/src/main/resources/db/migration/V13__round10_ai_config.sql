-- Round 10: AI Features config on the restaurant row - bring-your-own-key, multi-provider
-- (OpenAI / Anthropic / Gemini), off by default. See Restaurant.java's javadoc on each field for
-- exactly what each column gates.

ALTER TABLE restaurant ADD COLUMN ai_provider VARCHAR(20);
ALTER TABLE restaurant ADD COLUMN ai_api_key VARCHAR(500);
ALTER TABLE restaurant ADD COLUMN ai_model VARCHAR(100);
ALTER TABLE restaurant ADD COLUMN ai_features_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE restaurant ADD COLUMN ai_menu_import_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE restaurant ADD COLUMN ai_insights_chat_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE restaurant ADD COLUMN ai_reorder_drafts_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE restaurant ADD COLUMN ai_anomaly_flagging_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE restaurant ADD COLUMN ai_menu_descriptions_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE restaurant ADD COLUMN ai_nightly_summary_enabled BOOLEAN NOT NULL DEFAULT false;
