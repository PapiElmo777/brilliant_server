ALTER TABLE sessions ADD COLUMN last_seen_at TEXT;
UPDATE sessions SET last_seen_at = created_at;
UPDATE sessions SET revoked_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE revoked_at IS NULL AND rowid NOT IN (
    SELECT MIN(rowid) FROM sessions WHERE revoked_at IS NULL GROUP BY user_id
);
CREATE UNIQUE INDEX one_active_session_per_user ON sessions(user_id) WHERE revoked_at IS NULL;
