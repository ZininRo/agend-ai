-- Requires an EMPTY database; do not apply to populated tables.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM items LIMIT 1) OR EXISTS (SELECT 1 FROM messages LIMIT 1)
 OR EXISTS (SELECT 1 FROM sessions LIMIT 1) THEN
  RAISE EXCEPTION 'Encryption migration requires empty items/messages/sessions; backup and reset explicitly';
 END IF;
END $$;
DROP INDEX IF EXISTS idx_items_fts;
DROP INDEX IF EXISTS idx_items_title_trgm;
ALTER TABLE items ALTER COLUMN title TYPE TEXT, ALTER COLUMN place TYPE TEXT;
ALTER TABLE messages ALTER COLUMN intent_payload TYPE TEXT USING intent_payload::TEXT;
ALTER TABLE messages ALTER COLUMN response_json TYPE TEXT USING response_json::TEXT;
ALTER TABLE sessions ALTER COLUMN pending_clarification TYPE TEXT USING pending_clarification::TEXT;
