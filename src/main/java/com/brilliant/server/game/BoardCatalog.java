package com.brilliant.server.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

/** Versioned copy of the Flutter maps, checked by the shared parity suite. */
public final class BoardCatalog {
    public record Cell(String id, int row, int column, boolean initial) {}
    public record Zone(String id, Rule rule, List<Integer> prizes, List<Cell> cells) {
        public Zone { prizes = List.copyOf(prizes); cells = List.copyOf(cells); }
        public int prize(int place) { return place >= 1 && place <= 3 ? prizes.get(place - 1) : 0; }
    }
    public record Board(String id, List<Zone> zones) {
        public Board { zones = List.copyOf(zones); }
    }
    public record Catalog(int rulesVersion, List<Board> boards) {
        public Catalog { boards = List.copyOf(boards); }
    }
    public enum Rule {
        RED, YELLOW, BLUE, WHITE, GREEN, PURPLE, ORANGE_LOW, ORANGE_HIGH;
        public boolean accepts(Collection<Integer> values) {
            if (values.stream().anyMatch(v -> v == null || v < 1 || v > 6)) return false;
            long distinct = values.stream().distinct().count();
            return switch (this) {
                case RED, YELLOW -> distinct == values.size();
                case BLUE, WHITE -> distinct <= 1;
                case GREEN -> true;
                case PURPLE -> distinct <= 2;
                case ORANGE_LOW -> values.stream().allMatch(v -> v <= 3);
                case ORANGE_HIGH -> values.stream().allMatch(v -> v >= 4);
            };
        }
    }
    private final Catalog catalog;
    public BoardCatalog() {
        try (var stream = BoardCatalog.class.getResourceAsStream("/multiplayer/catalog-v1.json")) {
            if (stream == null) throw new IllegalStateException("Missing rules catalog");
            catalog = new ObjectMapper().readValue(stream, Catalog.class);
            if (catalog.rulesVersion() != 1 || catalog.boards().size() != 4) throw new IllegalStateException("Unsupported catalog");
            for (var board : catalog.boards()) validate(board);
        } catch (IOException e) { throw new IllegalStateException("Invalid rules catalog", e); }
    }
    public Catalog catalog() { return catalog; }
    public Board board(String id) {
        return catalog.boards().stream().filter(b -> b.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown board"));
    }
    private static void validate(Board board) {
        Set<String> ids = new HashSet<>(), positions = new HashSet<>(), zones = new HashSet<>();
        int initial = 0;
        for (var zone : board.zones()) {
            if (!zones.add(zone.id()) || zone.prizes().size() != 3 || zone.cells().isEmpty())
                throw new IllegalStateException("Invalid zone");
            for (var cell : zone.cells()) {
                if (!ids.add(cell.id()) || !positions.add(cell.row() + ":" + cell.column())
                        || cell.row() < 0 || cell.row() > 6 || cell.column() < 0 || cell.column() > 6)
                    throw new IllegalStateException("Invalid cell");
                if (cell.initial()) initial++;
            }
        }
        if (ids.size() != 49 || initial != 6) throw new IllegalStateException("Invalid geometry");
    }
}
