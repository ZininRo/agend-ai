-- Auth sessions will be introduced separately. Keep browser/dialog state explicitly named.
ALTER TABLE sessions RENAME TO chat_sessions;
