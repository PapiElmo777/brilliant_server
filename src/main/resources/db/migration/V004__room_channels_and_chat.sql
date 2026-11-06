ALTER TABLE room_members ADD COLUMN channel_id TEXT;
ALTER TABLE room_members ADD COLUMN channel_connected INTEGER NOT NULL DEFAULT 0 CHECK(channel_connected IN (0,1));
CREATE TABLE room_messages (
    id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL REFERENCES rooms(id),
    sequence INTEGER NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('PLAYER','SYSTEM')),
    author_member_id TEXT REFERENCES room_members(id),
    author_name TEXT,
    text TEXT NOT NULL,
    server_time TEXT NOT NULL,
    UNIQUE(room_id,sequence)
);
CREATE INDEX room_message_history ON room_messages(room_id,sequence);
