-- Bistrodesk branch-isolation release (requirement #4 of the follow-up 10-item list): "write a
-- whatsapp meta/twilio/360dialog integration and make it configurable inside setting ... put
-- branch owner phone number as sender." Per-branch, not a Restaurant-level column - see
-- Branch.whatsappProvider's javadoc for why a shared install-wide account can't model "each
-- branch's own registered WhatsApp Business number." Nullable and purely additive: every existing
-- branch starts unconfigured (whatsapp_provider IS NULL), and PurchaseOrderService falls back to
-- today's client-side wa.me deep link whenever that's the case - zero behavior change until an
-- owner actually fills these in for a branch.

ALTER TABLE branch ADD COLUMN whatsapp_provider VARCHAR(20);
ALTER TABLE branch ADD COLUMN whatsapp_sender_number VARCHAR(32);
ALTER TABLE branch ADD COLUMN whatsapp_api_key VARCHAR(500);
ALTER TABLE branch ADD COLUMN whatsapp_account_id VARCHAR(200);
