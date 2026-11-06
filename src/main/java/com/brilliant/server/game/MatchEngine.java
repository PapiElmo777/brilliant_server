package com.brilliant.server.game;

import com.brilliant.server.auth.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Persistence operations run inside the caller's BEGIN IMMEDIATE transaction. */
@Component
public class MatchEngine {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private final BoardCatalog catalog=new BoardCatalog();
    public MatchEngine(JdbcTemplate db,ObjectMapper json,Clock clock) {this.db=db;this.json=json;this.clock=clock;}
    private String now() {return clock.instant().toString();}
    private String encode(Object object) {try{return json.writeValueAsString(object);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Integer> board(String value) {try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    public Map<String,Object> match(String id) {
        var rows=db.queryForList("SELECT * FROM room_matches WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(404,"MATCH_NOT_FOUND");return rows.getFirst();
    }
    private List<Map<String,Object>> active(String matchId) {
        return db.queryForList("SELECT p.*,m.username FROM match_players p JOIN room_members m ON m.id=p.member_id WHERE p.match_id=? AND m.left_at IS NULL AND m.user_id IS NOT NULL ORDER BY m.join_order",matchId);
    }
    public void initialize(String matchId,String roomId) {
        db.update("INSERT INTO match_players(match_id,member_id) SELECT ?,id FROM room_members WHERE room_id=? AND left_at IS NULL",matchId,roomId);
    }
    private Map<String,Object> player(String id,String member) {
        var rows=db.queryForList("SELECT * FROM match_players WHERE match_id=? AND member_id=?",id,member);
        if(rows.isEmpty()) throw new ApiException(403,"NOT_MATCH_PARTICIPANT");return rows.getFirst();
    }
    public void prepare(String id,String member,Map<String,Integer> placements,BiConsumer<String,String> event) {
        var match=match(id);var player=player(id,member);
        if(!"PREPARING".equals(match.get("state"))) throw new ApiException(409,"PREPARATION_CLOSED");
        if(player.get("prepared_at")!=null) throw new ApiException(409,"PREPARATION_ALREADY_CONFIRMED");
        Map<String,Integer> valid;
        try {valid=new BoardRules(catalog.board((String)match.get("level_id"))).prepare(placements);}
        catch(IllegalArgumentException e) {throw new ApiException(400,"INVALID_PREPARATION");}
        db.update("UPDATE match_players SET board_json=?,prepared_at=? WHERE match_id=? AND member_id=?",encode(valid),now(),id,member);
        event.accept("PREPARATION_CONFIRMED",null);
        advance(id,event);
    }
    private Map<String,Object> round(String id) {
        var rows=db.queryForList("SELECT * FROM match_rounds WHERE match_id=? ORDER BY number DESC LIMIT 1",id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void openRound(String id,BiConsumer<String,String> event) {
        long number=db.queryForObject("SELECT COALESCE(MAX(number),0)+1 FROM match_rounds WHERE match_id=?",Long.class,id);
        int first=random.nextInt(6)+1,second=random.nextInt(6)+1;
        Instant started=clock.instant();
        db.update("INSERT INTO match_rounds VALUES(?,?,?,?,?,?,?,NULL)",UUID.randomUUID().toString(),id,number,first,second,started.toString(),started.plusSeconds(60).toString());
        db.update("UPDATE room_matches SET state='ROUND_ACTIVE' WHERE id=?",id);
        db.update("UPDATE rooms SET state='PLAYING' WHERE id=(SELECT room_id FROM room_matches WHERE id=?)",id);
        event.accept("ROUND_STARTED","Comienza la ronda "+number+". Tienen 60 segundos.");
    }
    public void action(String id,String roundId,String member,String kind,Integer die,String cell,BiConsumer<String,String> event) {
        var match=match(id);var player=player(id,member);var round=round(id);
        if(!"ROUND_ACTIVE".equals(match.get("state")) || round==null) throw new ApiException(409,"PREPARATION_PENDING");
        if(!roundId.equals(round.get("id")) || round.get("closed_at")!=null) throw new ApiException(409,"ROUND_EXPIRED");
        if(!Instant.parse((String)round.get("deadline")).isAfter(clock.instant())) throw new ApiException(409,"ROUND_EXPIRED");
        if(!db.queryForList("SELECT 1 FROM round_actions WHERE round_id=? AND member_id=?",roundId,member).isEmpty()) throw new ApiException(409,"ACTION_ALREADY_RESOLVED");
        if("PLACE".equals(kind)) {
            if(die==null || cell==null) throw new ApiException(400,"VALIDATION_ERROR");
            Map<String,Integer> updated;
            try {updated=new BoardRules(catalog.board((String)match.get("level_id"))).place(board((String)player.get("board_json")),((Number)round.get("first_die")).intValue(),((Number)round.get("second_die")).intValue(),die,cell);}
            catch(IllegalArgumentException e) {throw new ApiException(400,"INVALID_MOVE");}
            db.update("UPDATE match_players SET board_json=? WHERE match_id=? AND member_id=?",encode(updated),id,member);
        } else if(!"SKIP".equals(kind) || die!=null || cell!=null) throw new ApiException(400,"VALIDATION_ERROR");
        db.update("INSERT INTO round_actions VALUES(?,?,?,?,?,'PLAYER',?)",roundId,member,kind,die,cell,now());
        event.accept("ACTION_RESOLVED",null);
        advance(id,event);
    }
    public void advance(String id,BiConsumer<String,String> event) {
        var match=match(id);var active=active(id);if(active.isEmpty()) return;
        if("PREPARING".equals(match.get("state"))) {
            if(active.stream().allMatch(p->p.get("prepared_at")!=null)) openRound(id,event);
            return;
        }
        if(!"ROUND_ACTIVE".equals(match.get("state"))) return;
        var round=round(id);if(round==null || round.get("closed_at")!=null) return;
        String roundId=(String)round.get("id");
        if(!Instant.parse((String)round.get("deadline")).isAfter(clock.instant())) {
            for(var player:active) {
                int inserted=db.update("INSERT OR IGNORE INTO round_actions VALUES(?,?,'SKIP',NULL,NULL,'TIMEOUT',?)",roundId,player.get("member_id"),now());
                if(inserted>0) event.accept("ACTION_RESOLVED",player.get("username")+" omitió la ronda por tiempo agotado.");
            }
        }
        boolean done=active.stream().allMatch(p->!db.queryForList("SELECT 1 FROM round_actions WHERE round_id=? AND member_id=?",roundId,p.get("member_id")).isEmpty());
        if(done && db.update("UPDATE match_rounds SET closed_at=? WHERE id=? AND closed_at IS NULL",now(),roundId)==1) openRound(id,event);
    }
    public Map<String,Object> publicView(Map<String,Object> match) {
        String id=(String)match.get("id");var result=new LinkedHashMap<String,Object>();
        result.put("id",id);result.put("state",match.get("state"));result.put("levelId",match.get("level_id"));result.put("target",match.get("target"));
        try {result.put("participants",json.readTree((String)match.get("participants_json")));}catch(Exception e){throw new IllegalStateException(e);}
        result.put("preparedMemberIds",db.queryForList("SELECT member_id FROM match_players WHERE match_id=? AND prepared_at IS NOT NULL ORDER BY member_id",String.class,id));
        result.put("ownBoard",Map.of());result.put("ownPreparationConfirmed",false);result.put("round",null);result.put("result",null);
        var round=round(id);
        if(round!=null) {
            var data=new LinkedHashMap<String,Object>();data.put("id",round.get("id"));data.put("number",round.get("number"));
            data.put("dice",List.of(round.get("first_die"),round.get("second_die")));data.put("startedAt",round.get("started_at"));data.put("deadline",round.get("deadline"));
            data.put("resolvedMemberIds",db.queryForList("SELECT member_id FROM round_actions WHERE round_id=? ORDER BY member_id",String.class,round.get("id")));
            data.put("ownAction",null);result.put("round",data);
        }
        return result;
    }
    public Map<String,Object> privateView(String id,String member) {
        var rows=db.queryForList("SELECT * FROM match_players WHERE match_id=? AND member_id=?",id,member);
        var result=new LinkedHashMap<String,Object>();
        result.put("ownBoard",rows.isEmpty()?Map.of():board((String)rows.getFirst().get("board_json")));
        result.put("ownPreparationConfirmed",!rows.isEmpty() && rows.getFirst().get("prepared_at")!=null);
        result.put("ownAction",null);var round=round(id);
        if(round!=null) {
            var actions=db.queryForList("SELECT * FROM round_actions WHERE round_id=? AND member_id=?",round.get("id"),member);
            if(!actions.isEmpty()) {
                var a=actions.getFirst();var action=new LinkedHashMap<String,Object>();action.put("kind",a.get("kind"));action.put("dieIndex",a.get("die_index"));action.put("cellId",a.get("cell_id"));action.put("reason",a.get("reason"));result.put("ownAction",action);
            }
        }
        return result;
    }
}
