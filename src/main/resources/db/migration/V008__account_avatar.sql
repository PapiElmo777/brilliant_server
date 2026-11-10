ALTER TABLE users ADD COLUMN avatar_id TEXT CHECK(avatar_id IS NULL OR avatar_id IN ('tablero_01','tablero_02','tablero_03','tablero_04'));
