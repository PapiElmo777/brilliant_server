package com.brilliant.server.game;

import java.util.*;
import com.brilliant.server.game.BoardCatalog.*;

/** Pure domain: no clock, HTTP or database; rejected commands never mutate a board. */
public final class BoardRules {
    private final Board board;
    private final Map<String, Cell> cells = new LinkedHashMap<>();
    private final Map<String, Zone> zones = new HashMap<>();
    public BoardRules(Board board) {
        this.board = board;
        for (var zone : board.zones()) for (var cell : zone.cells()) {
            cells.put(cell.id(), cell); zones.put(cell.id(), zone);
        }
    }
    public Map<String, Integer> prepare(Map<String, Integer> placements) {
        Set<String> initial = new HashSet<>();
        cells.values().stream().filter(Cell::initial).forEach(c -> initial.add(c.id()));
        if (!placements.keySet().equals(initial) || !new HashSet<>(placements.values()).equals(Set.of(1,2,3,4,5,6)))
            throw new IllegalArgumentException("INVALID_PREPARATION");
        validate(placements);
        return Map.copyOf(placements);
    }
    /** Accepts a partial board for parity checks and reconstruction from trusted storage. */
    public void validate(Map<String, Integer> placements) {
        if (!cells.keySet().containsAll(placements.keySet())) throw new IllegalArgumentException("UNKNOWN_CELL");
        for (var zone : board.zones()) {
            var values = new ArrayList<Integer>();
            for (var cell : zone.cells()) if (placements.containsKey(cell.id())) values.add(placements.get(cell.id()));
            if (!zone.rule().accepts(values)) throw new IllegalArgumentException("INVALID_BOARD");
        }
    }
    public Set<String> destinations(Map<String, Integer> placements, int value, int anchor) {
        validate(placements);
        if (value < 1 || value > 6 || anchor < 1 || anchor > 6) throw new IllegalArgumentException("INVALID_DICE");
        Set<String> result = new TreeSet<>();
        for (var target : cells.values()) {
            if (placements.containsKey(target.id())) continue;
            boolean adjacent = cells.values().stream().anyMatch(c -> Objects.equals(placements.get(c.id()), anchor)
                && Math.abs(c.row() - target.row()) + Math.abs(c.column() - target.column()) == 1);
            if (!adjacent) continue;
            var values = new ArrayList<Integer>();
            for (var cell : zones.get(target.id()).cells()) if (placements.containsKey(cell.id())) values.add(placements.get(cell.id()));
            values.add(value);
            if (zones.get(target.id()).rule().accepts(values)) result.add(target.id());
        }
        return Collections.unmodifiableSet(result);
    }
    public Map<String, Integer> place(Map<String, Integer> placements, int firstDie, int secondDie, int dieIndex, String cellId) {
        if (dieIndex != 0 && dieIndex != 1) throw new IllegalArgumentException("INVALID_DIE_INDEX");
        int value = dieIndex == 0 ? firstDie : secondDie, anchor = dieIndex == 0 ? secondDie : firstDie;
        if (!destinations(placements, value, anchor).contains(cellId)) throw new IllegalArgumentException("INVALID_MOVE");
        var result = new HashMap<>(placements); result.put(cellId, value);
        return Map.copyOf(result);
    }
    public boolean hasMove(Map<String, Integer> placements, int first, int second) {
        return !destinations(placements, first, second).isEmpty() || !destinations(placements, second, first).isEmpty();
    }
    public Set<String> completedZones(Map<String, Integer> placements) {
        validate(placements);
        Set<String> result = new TreeSet<>();
        for (var zone : board.zones()) if (zone.cells().stream().allMatch(c -> placements.containsKey(c.id()))) result.add(zone.id());
        return Collections.unmodifiableSet(result);
    }
}
