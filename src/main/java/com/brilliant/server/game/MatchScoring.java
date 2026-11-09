package com.brilliant.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Claims, scores and the frozen result share the caller's match transaction. */
@Component
public class MatchScoring {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final BoardCatalog catalog=new BoardCatalog();
    public MatchScoring(JdbcTemplate db,ObjectMapper json,Clock clock) {this.db=db;this.json=json;this.clock=clock;}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    public Object result(String id) {
        String value=db.queryForObject("SELECT result_json FROM match_progress WHERE match_id=?",String.class,id);
        try{return value==null?null:json.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException(e);}
    }
    public List<Map<String,Object>> scores(String id) {
        return db.queryForList("""
            SELECT m.id AS memberId,m.username,m.join_order AS joinOrder,
              CASE WHEN m.left_at IS NOT NULL OR m.user_id IS NULL THEN 1 ELSE 0 END AS abandoned,
              COALESCE(SUM(c.points),0) AS points,COUNT(c.zone_id) AS completedZones,
              COALESCE(MAX(CASE WHEN c.points>0 THEN c.sequence END),0) AS scoreSequence
            FROM match_players p JOIN room_members m ON m.id=p.member_id
            LEFT JOIN zone_claims c ON c.match_id=p.match_id AND c.member_id=p.member_id
            WHERE p.match_id=? GROUP BY m.id ORDER BY m.join_order
            """,id);
    }
    public List<Map<String,Object>> claims(String id) {
        return db.queryForList("SELECT zone_id AS zoneId,member_id AS memberId,place,points,sequence FROM zone_claims WHERE match_id=? ORDER BY sequence,zone_id",id);
    }
    public List<Map<String,Object>> zones(String id,String level) {
        var claims=claims(id);var result=new ArrayList<Map<String,Object>>();
        for(var zone:catalog.board(level).zones()) {
            int place=(int)claims.stream().filter(c->zone.id().equals(c.get("zoneId"))).count()+1;
            result.add(Map.of("zoneId",zone.id(),"nextPlace",place,"nextPoints",zone.prize(place)));
        }
        return result;
    }
    public void award(Map<String,Object> match,String member,Map<String,Integer> board,BiConsumer<String,String> event) {
        String id=(String)match.get("id"),room=(String)match.get("room_id"),level=(String)match.get("level_id");
        var completed=new BoardRules(catalog.board(level)).completedZones(board);
        String username=db.queryForObject("SELECT username FROM room_members WHERE id=?",String.class,member);
        for(var zone:catalog.board(level).zones()) {
            if(!completed.contains(zone.id()) || !db.queryForList("SELECT 1 FROM zone_claims WHERE match_id=? AND zone_id=? AND member_id=?",id,zone.id(),member).isEmpty()) continue;
            int place=db.queryForObject("SELECT COUNT(*)+1 FROM zone_claims WHERE match_id=? AND zone_id=?",Integer.class,id,zone.id());
            long sequence=db.queryForObject("SELECT sequence+1 FROM rooms WHERE id=?",Long.class,room);
            db.update("INSERT INTO zone_claims VALUES(?,?,?,?,?,?)",id,zone.id(),member,place,zone.prize(place),sequence);
            event.accept("ZONE_CLAIMED",username+" completó "+zone.id()+" en el puesto "+place+": +"+zone.prize(place)+" puntos.");
        }
    }
    public boolean target(Map<String,Object> match,String member,BiConsumer<String,String> event) {
        int points=scores((String)match.get("id")).stream().filter(p->member.equals(p.get("memberId"))).mapToInt(p->((Number)p.get("points")).intValue()).findFirst().orElse(0);
        if(points<((Number)match.get("target")).intValue()) return false;
        finish(match,member,"TARGET",event);return true;
    }
    public void finish(Map<String,Object> match,String winner,String reason,BiConsumer<String,String> event) {
        String id=(String)match.get("id");
        if(result(id)!=null) return;
        // Winner first; departures follow active participants; absolute ties use join order.
        var ranking=new ArrayList<>(scores(id));
        ranking.sort(Comparator.<Map<String,Object>>comparingInt(p->winner!=null && winner.equals(p.get("memberId"))?0:1)
            .thenComparingInt(p->((Number)p.get("abandoned")).intValue())
            .thenComparing(Comparator.<Map<String,Object>>comparingInt(p->((Number)p.get("points")).intValue()).reversed())
            .thenComparing(Comparator.<Map<String,Object>>comparingInt(p->((Number)p.get("completedZones")).intValue()).reversed())
            .thenComparingLong(p->((Number)p.get("scoreSequence")).longValue())
            .thenComparingLong(p->((Number)p.get("joinOrder")).longValue()));
        for(int i=0;i<ranking.size();i++) ranking.get(i).put("position",i+1);
        var result=new LinkedHashMap<String,Object>();result.put("winnerMemberId",winner);result.put("reason",reason);result.put("ranking",ranking);result.put("finishedAt",clock.instant().toString());
        db.update("UPDATE match_progress SET result_json=? WHERE match_id=? AND result_json IS NULL",encode(result),id);
        db.update("UPDATE room_matches SET state='FINISHED' WHERE id=?",id);
        db.update("UPDATE rooms SET state=CASE WHEN state='CLOSED' THEN 'CLOSED' ELSE 'FINISHED' END WHERE id=?",match.get("room_id"));
        db.update("UPDATE match_rounds SET closed_at=COALESCE(closed_at,?) WHERE match_id=?",clock.instant().toString(),id);
        event.accept("MATCH_FINISHED",winner==null?"La partida terminó sin participantes.":ranking.getFirst().get("username")+switch(reason){case "TARGET"->" ganó al alcanzar la meta.";case "END_VOTE"->" ganó por votación.";default->" ganó por abandono.";});
    }
}
