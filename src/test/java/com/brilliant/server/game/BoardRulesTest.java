package com.brilliant.server.game;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoardRulesTest {
    private final BoardCatalog catalog = new BoardCatalog();
    @Test void sharedDartCorpus() throws Exception {
        var mapper = new ObjectMapper();
        try (var stream = getClass().getResourceAsStream("/multiplayer/parity-v1.json")) {
            var corpus = mapper.readTree(stream);
            assertEquals(1, corpus.path("rulesVersion").asInt());
            assertTrue(corpus.path("states").size() >= 40);
            for (var state : corpus.path("states")) {
                var rules = new BoardRules(catalog.board(state.path("boardId").asText()));
                Map<String, Integer> values = mapper.convertValue(state.path("placements"), new TypeReference<>() {});
                rules.validate(values);
                if (state.path("preparation").asBoolean()) {
                    assertEquals(values, rules.prepare(values));
                    var invalid = new HashMap<>(values); invalid.remove(invalid.keySet().iterator().next());
                    assertThrows(IllegalArgumentException.class, () -> rules.prepare(invalid));
                    var duplicate = new HashMap<>(values);
                    for (var key : values.keySet()) duplicate.put(key, 1);
                    assertThrows(IllegalArgumentException.class, () -> rules.prepare(duplicate));
                }
                Set<String> complete = mapper.convertValue(state.path("completedZones"), new TypeReference<>() {});
                assertEquals(complete, rules.completedZones(values));
                for (var probe : state.path("probes")) {
                    int value = probe.path("value").asInt(), anchor = probe.path("anchor").asInt();
                    Set<String> expected = mapper.convertValue(probe.path("destinations"), new TypeReference<>() {});
                    assertEquals(expected, rules.destinations(values, value, anchor), state.path("boardId") + ":" + value + ":" + anchor);
                    for (var zone : catalog.board(state.path("boardId").asText()).zones()) for (var cell : zone.cells()) {
                        if (expected.contains(cell.id())) {
                            assertEquals(value, rules.place(values, value, anchor, 0, cell.id()).get(cell.id()));
                            assertEquals(value, rules.place(values, anchor, value, 1, cell.id()).get(cell.id()));
                        } else assertThrows(IllegalArgumentException.class, () -> rules.place(values, value, anchor, 0, cell.id()));
                    }
                }
            }
        }
    }
    @Test void rejectsUntrustedValuesAndPreservesInput() {
        var rules = new BoardRules(catalog.board("tablero_01"));
        var values = new HashMap<String,Integer>(); values.put("f1_c2", 1);
        assertThrows(IllegalArgumentException.class, () -> rules.place(values, 1, 2, 2, "f1_c1"));
        assertThrows(IllegalArgumentException.class, () -> rules.destinations(values, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> rules.validate(Map.of("unknown", 1)));
        assertThrows(IllegalArgumentException.class, () -> rules.validate(Map.of("f1_c2", 7)));
        assertEquals(Map.of("f1_c2", 1), values);
    }
    @Test void targetsPrizesAndRanking() {
        assertEquals(List.of(26,24,22,20,20), java.util.stream.IntStream.rangeClosed(2,6).map(Scoring::target).boxed().toList());
        assertThrows(IllegalArgumentException.class, () -> Scoring.target(7));
        for (var board : catalog.catalog().boards()) for (var zone : board.zones()) {
            for (int place = 4; place <= 6; place++) assertEquals(0, zone.prize(place));
        }
        var a = new Scoring.Score("a", 10, 2, 5);
        var b = new Scoring.Score("b", 10, 3, 8);
        var c = new Scoring.Score("c", 10, 3, 6);
        var d = new Scoring.Score("d", 0, 0, 0);
        var e = new Scoring.Score("e", 0, 0, 0);
        var ranks = Scoring.ranking(List.of(a,b,c,d,e));
        assertEquals(List.of("c","b","a","d","e"), ranks.stream().map(r -> r.score().playerId()).toList());
        assertEquals(List.of(1,2,3,4,4), ranks.stream().map(Scoring.Position::position).toList());
        assertThrows(IllegalArgumentException.class, () -> Scoring.ranking(List.of(a,a)));
    }
}
