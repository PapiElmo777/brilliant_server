package com.brilliant.server.game;

import java.util.*;

public final class Scoring {
    private Scoring() {}
    public static int target(int players) {
        return switch(players) { case 2 -> 26; case 3 -> 24; case 4 -> 22; case 5,6 -> 20;
            default -> throw new IllegalArgumentException("Players must be 2–6"); };
    }
    /** Sequence of the last positive award: a zero-point completion does not reset it. */
    public record Score(String playerId, int points, int completedZones, long scoreSequence) {}
    public record Position(Score score, int position) {}
    public static final Comparator<Score> ORDER = Comparator.comparingInt(Score::points).reversed()
        .thenComparing(Comparator.comparingInt(Score::completedZones).reversed()).thenComparingLong(Score::scoreSequence);
    /** Absolute ties share a position until the final tie policy is confirmed. */
    public static List<Position> ranking(List<Score> scores) {
        if (scores.stream().map(Score::playerId).distinct().count() != scores.size()) throw new IllegalArgumentException("Duplicate participant");
        var ordered = new ArrayList<>(scores); ordered.sort(ORDER);
        var result = new ArrayList<Position>();
        for (int i = 0; i < ordered.size(); i++) {
            int position = i > 0 && ORDER.compare(ordered.get(i-1), ordered.get(i)) == 0 ? result.getLast().position() : i + 1;
            result.add(new Position(ordered.get(i), position));
        }
        return List.copyOf(result);
    }
}
