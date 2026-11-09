CREATE TABLE match_progress (
    match_id TEXT PRIMARY KEY REFERENCES room_matches(id),
    blocked_rounds INTEGER NOT NULL DEFAULT 0,
    result_json TEXT
);
INSERT INTO match_progress(match_id) SELECT id FROM room_matches;
CREATE TABLE zone_claims (
    match_id TEXT NOT NULL REFERENCES room_matches(id),
    zone_id TEXT NOT NULL,
    member_id TEXT NOT NULL REFERENCES room_members(id),
    place INTEGER NOT NULL CHECK(place BETWEEN 1 AND 6),
    points INTEGER NOT NULL CHECK(points >= 0),
    sequence INTEGER NOT NULL,
    PRIMARY KEY(match_id,zone_id,member_id),
    UNIQUE(match_id,zone_id,place)
);
CREATE TABLE end_votes (
    id TEXT PRIMARY KEY,
    match_id TEXT NOT NULL REFERENCES room_matches(id),
    started_at TEXT NOT NULL,
    deadline TEXT NOT NULL,
    outcome TEXT CHECK(outcome IN ('APPROVED','REJECTED','EXPIRED')),
    closed_at TEXT
);
CREATE UNIQUE INDEX one_open_vote ON end_votes(match_id) WHERE closed_at IS NULL;
CREATE TABLE end_ballots (
    vote_id TEXT NOT NULL REFERENCES end_votes(id),
    member_id TEXT NOT NULL REFERENCES room_members(id),
    approve INTEGER NOT NULL CHECK(approve IN (0,1)),
    PRIMARY KEY(vote_id,member_id)
);
