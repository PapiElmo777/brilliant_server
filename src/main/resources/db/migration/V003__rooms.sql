CREATE TABLE rooms (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    host_member_id TEXT,
    capacity INTEGER NOT NULL CHECK(capacity BETWEEN 2 AND 6),
    level_id TEXT,
    state TEXT NOT NULL CHECK(state IN ('LOBBY','PREPARING','PLAYING','FINISHED','CLOSED')),
    revision INTEGER NOT NULL DEFAULT 0,
    sequence INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);
CREATE TABLE room_members (
    id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL REFERENCES rooms(id),
    user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
    session_id TEXT NOT NULL,
    username TEXT NOT NULL,
    join_order INTEGER NOT NULL,
    ready INTEGER NOT NULL DEFAULT 0 CHECK(ready IN (0,1)),
    last_seen_at TEXT NOT NULL,
    left_at TEXT,
    UNIQUE(room_id,join_order)
);
CREATE UNIQUE INDEX one_active_room_per_user ON room_members(user_id) WHERE left_at IS NULL AND user_id IS NOT NULL;
CREATE INDEX room_active_members ON room_members(room_id,left_at);
CREATE TABLE room_matches (
    id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL REFERENCES rooms(id),
    level_id TEXT NOT NULL,
    rules_version INTEGER NOT NULL,
    target INTEGER NOT NULL,
    state TEXT NOT NULL,
    participants_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE room_commands (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    command_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    response_json TEXT NOT NULL,
    PRIMARY KEY(user_id,command_id)
);
CREATE TABLE room_events (
    room_id TEXT NOT NULL REFERENCES rooms(id),
    sequence INTEGER NOT NULL,
    type TEXT NOT NULL,
    server_time TEXT NOT NULL,
    data_json TEXT NOT NULL,
    PRIMARY KEY(room_id,sequence)
);
