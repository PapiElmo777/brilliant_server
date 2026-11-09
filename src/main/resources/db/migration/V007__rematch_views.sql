ALTER TABLE room_members ADD COLUMN view_match_id TEXT REFERENCES room_matches(id);
UPDATE room_members SET view_match_id=(
    SELECT p.match_id FROM match_players p JOIN room_matches g ON g.id=p.match_id
    WHERE p.member_id=room_members.id ORDER BY g.rowid DESC LIMIT 1
);
ALTER TABLE rooms ADD COLUMN rematch_source_id TEXT REFERENCES room_matches(id);
CREATE TABLE rematch_acceptances (
    match_id TEXT NOT NULL REFERENCES room_matches(id),
    member_id TEXT NOT NULL REFERENCES room_members(id),
    accepted_at TEXT NOT NULL,
    PRIMARY KEY(match_id,member_id)
);
CREATE TABLE room_member_events (
    room_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    member_id TEXT NOT NULL REFERENCES room_members(id),
    snapshot_json TEXT NOT NULL,
    PRIMARY KEY(room_id,sequence,member_id),
    FOREIGN KEY(room_id,sequence) REFERENCES room_events(room_id,sequence)
);
