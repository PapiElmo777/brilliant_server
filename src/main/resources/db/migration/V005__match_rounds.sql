CREATE TABLE match_players (
    match_id TEXT NOT NULL REFERENCES room_matches(id),
    member_id TEXT NOT NULL REFERENCES room_members(id),
    board_json TEXT NOT NULL DEFAULT '{}',
    prepared_at TEXT,
    PRIMARY KEY(match_id,member_id)
);
CREATE TABLE match_rounds (
    id TEXT PRIMARY KEY,
    match_id TEXT NOT NULL REFERENCES room_matches(id),
    number INTEGER NOT NULL,
    first_die INTEGER NOT NULL CHECK(first_die BETWEEN 1 AND 6),
    second_die INTEGER NOT NULL CHECK(second_die BETWEEN 1 AND 6),
    started_at TEXT NOT NULL,
    deadline TEXT NOT NULL,
    closed_at TEXT,
    UNIQUE(match_id,number)
);
CREATE UNIQUE INDEX one_open_round ON match_rounds(match_id) WHERE closed_at IS NULL;
CREATE TABLE round_actions (
    round_id TEXT NOT NULL REFERENCES match_rounds(id),
    member_id TEXT NOT NULL REFERENCES room_members(id),
    kind TEXT NOT NULL CHECK(kind IN ('PLACE','SKIP')),
    die_index INTEGER,
    cell_id TEXT,
    reason TEXT NOT NULL CHECK(reason IN ('PLAYER','TIMEOUT')),
    accepted_at TEXT NOT NULL,
    PRIMARY KEY(round_id,member_id)
);
CREATE TABLE room_event_views (
    room_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    member_id TEXT NOT NULL REFERENCES room_members(id),
    private_json TEXT NOT NULL,
    PRIMARY KEY(room_id,sequence,member_id),
    FOREIGN KEY(room_id,sequence) REFERENCES room_events(room_id,sequence)
);
INSERT INTO match_players(match_id,member_id)
SELECT p.id,m.id FROM room_matches p
JOIN json_each(p.participants_json) participant
JOIN room_members m ON m.id=json_extract(participant.value,'$.memberId');
