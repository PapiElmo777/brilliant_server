package com.brilliant.server.rooms;

import com.brilliant.server.auth.*;
import com.brilliant.server.database.Database;
import com.brilliant.server.game.*;
import com.brilliant.server.users.Accounts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RoomService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Accounts accounts;
    private final Clock clock;
    private final ObjectMapper json;
    private final RateLimits limits;
    private final MatchEngine matches;
    private final SecureRandom random = new SecureRandom();
    private final BoardCatalog catalog = new BoardCatalog();
    public RoomService(JdbcTemplate db, TransactionTemplate tx, Accounts accounts, Clock clock, ObjectMapper json, RateLimits limits, MatchEngine matches) {
        this.db = db; this.tx = tx; this.accounts = accounts; this.clock = clock; this.json = json; this.limits = limits; this.matches=matches;
    }
    private String now() { return clock.instant().toString(); }
    private static void fail(int status, String code) { throw new ApiException(status, code); }
    private static void uuid(String id) {
        try { if (id == null || !UUID.fromString(id).toString().equals(id)) fail(400,"VALIDATION_ERROR"); }
        catch (IllegalArgumentException e) { fail(400,"VALIDATION_ERROR"); }
    }
    private Map<String,Object> room(String id) {
        var rows = db.queryForList("SELECT * FROM rooms WHERE id=?",id);
        if (rows.isEmpty()) fail(404,"ROOM_NOT_FOUND");
        return rows.getFirst();
    }
    private List<Map<String,Object>> members(String id) {
        return db.queryForList("SELECT * FROM room_members WHERE room_id=? AND left_at IS NULL ORDER BY join_order",id);
    }
    private List<Map<String,Object>> lobbyMembers(String id) {
        return members(id).stream().filter(m->m.get("view_match_id")==null).toList();
    }
    private void inLobby(Map<String,Object> member) {
        if(member.get("view_match_id")!=null) fail(409,"REMATCH_NOT_ACCEPTED");
    }
    private Map<String,Object> latestMatch(String id) {
        var rows=db.queryForList("SELECT * FROM room_matches WHERE room_id=? ORDER BY rowid DESC LIMIT 1",id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Map<String,Object> member(String id, String user) {
        return members(id).stream().filter(m -> user.equals(m.get("user_id"))).findFirst()
            .orElseThrow(() -> new ApiException(403,"NOT_ROOM_MEMBER"));
    }
    private void lobby(Map<String,Object> room) {
        if (!"LOBBY".equals(room.get("state"))) fail(409,"ROOM_ALREADY_STARTED");
    }
    private void host(Map<String,Object> room, Map<String,Object> member) {
        if (!member.get("id").equals(room.get("host_member_id"))) fail(403,"HOST_ONLY");
    }
    private void revision(Map<String,Object> room, Long expected) {
        if (expected == null || expected < 0) fail(400,"VALIDATION_ERROR");
        if (((Number)room.get("revision")).longValue() != expected) fail(409,"STALE_SETTINGS");
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Map<String,Object> decode(String value) {
        try { return json.readValue(value,new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String fingerprint(String operation, String resource, JsonNode body) {
        var sorted = new TreeMap<String,JsonNode>();
        body.fields().forEachRemaining(e -> { if (!e.getKey().equals("commandId")) sorted.put(e.getKey(),e.getValue()); });
        return AuthService.sha256(operation + "\n" + resource + "\n" + encode(canonical(json.valueToTree(sorted))));
    }
    private Object canonical(JsonNode value) {
        if(value.isObject()) {var fields=new TreeMap<String,Object>();value.fields().forEachRemaining(e->fields.put(e.getKey(),canonical(e.getValue())));return fields;}
        if(value.isArray()) {var items=new ArrayList<Object>();value.forEach(item->items.add(canonical(item)));return items;}
        return value;
    }
    /** BEGIN IMMEDIATE serializes the last slot, start/join and replay receipt atomically. */
    private Map<String,Object> command(String user, String sid, String commandId, String operation, String resource, JsonNode body, Supplier<String> effect) {
        uuid(commandId);
        advanceMatches();
        return Database.write(tx, () -> {
            accounts.requireActive(user,sid);
            String fingerprint = fingerprint(operation,resource,body);
            var prior = db.queryForList("SELECT * FROM room_commands WHERE user_id=? AND command_id=?",user,commandId);
            if (!prior.isEmpty()) {
                if (!fingerprint.equals(prior.getFirst().get("fingerprint"))) fail(409,"COMMAND_CONFLICT");
                return decode((String)prior.getFirst().get("response_json"));
            }
            String id = effect.get();
            var snapshot = snapshotFor(id,user);
            var response = Map.<String,Object>of("commandId",commandId,"roomId",id,"sequence",snapshot.get("sequence"),"data",snapshot);
            db.update("INSERT INTO room_commands VALUES(?,?,?,?)",user,commandId,fingerprint,encode(response));
            return response;
        });
    }
    public Map<String,Object> list(String user, String sid) {
        expireMembers();
        return Database.write(tx, () -> {
            accounts.requireActive(user,sid);
            var rooms = new ArrayList<Map<String,Object>>();
            for (var r : db.queryForList("SELECT * FROM rooms WHERE state='LOBBY' ORDER BY created_at,id")) {
                var active = lobbyMembers((String)r.get("id"));
                var host = active.stream().filter(m -> m.get("id").equals(r.get("host_member_id"))).findFirst();
                var item = new LinkedHashMap<String,Object>();
                item.put("id",r.get("id")); item.put("name",r.get("name")); item.put("hostName",host.map(m -> m.get("username")).orElse(""));
                item.put("capacity",r.get("capacity")); item.put("occupancy",active.size()); item.put("levelId",r.get("level_id")); item.put("state",r.get("state"));
                rooms.add(item);
            }
            var own = db.queryForList("SELECT room_id FROM room_members WHERE user_id=? AND left_at IS NULL",user);
            var result = new LinkedHashMap<String,Object>(); result.put("rooms",rooms);
            result.put("currentRoomId",own.isEmpty() ? null : own.getFirst().get("room_id"));
            return result;
        });
    }
    public Map<String,Object> get(String id, String user, String sid) {
        expireMembers();
        return Database.write(tx, () -> {
            accounts.requireActive(user,sid); room(id);
            var member = member(id,user);
            if (!sid.equals(member.get("session_id"))) fail(403,"NOT_ROOM_MEMBER");
            db.update("UPDATE room_members SET last_seen_at=? WHERE id=? AND channel_id IS NULL",now(),member.get("id"));
            return snapshotFor(id,user);
        });
    }
    public Map<String,Object> create(String user, String sid, String cid, JsonNode body, String name, Integer capacity) {
        if (name == null || name.strip().isEmpty() || name.strip().codePointCount(0,name.strip().length()) > 80
                || name.codePoints().anyMatch(Character::isISOControl) || capacity == null || capacity < 2 || capacity > 6) fail(400,"VALIDATION_ERROR");
        return command(user,sid,cid,"create","",body,() -> {
            available(user);
            String id = UUID.randomUUID().toString();
            db.update("INSERT INTO rooms(id,name,capacity,state,created_at) VALUES(?,?,?,'LOBBY',?)",id,name.strip(),capacity,now());
            String mid = addMember(id,user,sid);
            db.update("UPDATE rooms SET host_member_id=? WHERE id=?",mid,id);
            event(id,"MEMBER_JOINED",accounts.byId(user).user().username()+" se unió a la sala."); return id;
        });
    }
    private void available(String user) {
        if (!db.queryForList("SELECT id FROM room_members WHERE user_id=? AND left_at IS NULL",user).isEmpty()) fail(409,"ALREADY_IN_ROOM");
    }
    private String addMember(String id, String user, String sid) {
        String mid = UUID.randomUUID().toString();
        long order = db.queryForObject("SELECT COALESCE(MAX(join_order),0)+1 FROM room_members WHERE room_id=?",Long.class,id);
        db.update("INSERT INTO room_members(id,room_id,user_id,session_id,username,join_order,last_seen_at) VALUES(?,?,?,?,?,?,?)",
            mid,id,user,sid,accounts.byId(user).user().username(),order,now());
        return mid;
    }
    public Map<String,Object> join(String id, String user, String sid, String cid, JsonNode body) {
        return command(user,sid,cid,"join",id,body,() -> {
            var room = room(id); lobby(room); available(user);
            if (lobbyMembers(id).size() >= ((Number)room.get("capacity")).intValue()) fail(409,"ROOM_FULL");
            addMember(id,user,sid); event(id,"MEMBER_JOINED",accounts.byId(user).user().username()+" se unió a la sala."); return id;
        });
    }
    public Map<String,Object> settings(String id, String user, String sid, String cid, JsonNode body, Long expected, Integer capacity, String level, boolean chooseRandom) {
        return command(user,sid,cid,chooseRandom ? "random-level" : "settings",id,body,() -> {
            var room = room(id); var member = member(id,user); host(room,member); lobby(room); inLobby(member); revision(room,expected);
            int cap = chooseRandom ? ((Number)room.get("capacity")).intValue() : capacity == null ? 0 : capacity;
            if (cap < 2 || cap > 6) fail(400,"VALIDATION_ERROR");
            if (cap < lobbyMembers(id).size()) fail(409,"CAPACITY_BELOW_OCCUPANCY");
            String selected = chooseRandom ? catalog.catalog().boards().get(random.nextInt(4)).id() : level;
            if (selected != null && catalog.catalog().boards().stream().noneMatch(b -> b.id().equals(selected))) fail(400,"VALIDATION_ERROR");
            db.update("UPDATE rooms SET capacity=?,level_id=?,revision=revision+1 WHERE id=?",cap,selected,id);
            event(id,"SETTINGS_CHANGED"); return id;
        });
    }
    public Map<String,Object> ready(String id, String user, String sid, String cid, JsonNode body, Long expected, Boolean ready) {
        if (ready == null) fail(400,"VALIDATION_ERROR");
        return command(user,sid,cid,"ready",id,body,() -> {
            var room = room(id); var member = member(id,user); lobby(room); inLobby(member); revision(room,expected);
            if (member.get("id").equals(room.get("host_member_id"))) fail(409,"HOST_STARTS_MATCH");
            db.update("UPDATE room_members SET ready=?,last_seen_at=CASE WHEN channel_id IS NULL THEN ? ELSE last_seen_at END WHERE id=?",ready ? 1 : 0,now(),member.get("id"));
            event(id,"READY_CHANGED"); return id;
        });
    }
    public Map<String,Object> start(String id, String user, String sid, String cid, JsonNode body, Long expected) {
        return command(user,sid,cid,"start",id,body,() -> {
            var room = room(id); host(room,member(id,user)); lobby(room); revision(room,expected);
            var active = lobbyMembers(id);
            if (active.size() < 2) fail(409,"NOT_ENOUGH_PLAYERS");
            if (room.get("level_id") == null) fail(409,"LEVEL_REQUIRED");
            if (active.stream().anyMatch(m -> !connected(m) || (!m.get("id").equals(room.get("host_member_id")) && ((Number)m.get("ready")).intValue() == 0)))
                fail(409,"PLAYERS_NOT_READY");
            String matchId = UUID.randomUUID().toString();
            db.update("INSERT INTO room_matches VALUES(?,?,?,?,?,?,?,?)",matchId,id,room.get("level_id"),1,Scoring.target(active.size()),"PREPARING",encode(publicMembers(active)),now());
            db.update("UPDATE rooms SET state='PREPARING' WHERE id=?",id);
            for(var participant:active) db.update("UPDATE room_members SET view_match_id=? WHERE id=?",matchId,participant.get("id"));
            matches.initialize(matchId,id);
            event(id,"MATCH_STARTED"); return id;
        });
    }
    public Map<String,Object> leave(String id, String user, String sid, String cid, JsonNode body) {
        return command(user,sid,cid,"leave",id,body,() -> {
            room(id); remove(member(id,user)); return id;
        });
    }
    /** Called within the account transaction, before revocation/deletion. No cascading loss of history. */
    public void leaveAccount(String user) {
        for (var m : db.queryForList("SELECT * FROM room_members WHERE user_id=? AND left_at IS NULL",user)) remove(m);
    }
    private void remove(Map<String,Object> member) { remove(member,true); }
    private void remove(Map<String,Object> member,boolean advance) {
        String id = (String)member.get("room_id");
        if (db.update("UPDATE room_members SET left_at=?,ready=0 WHERE id=? AND left_at IS NULL",now(),member.get("id")) == 0) return;
        var room = room(id); var allRemaining = members(id);
        var latest=latestMatch(id);
        var remaining = "LOBBY".equals(room.get("state"))?lobbyMembers(id):latest==null?allRemaining:
            allRemaining.stream().filter(m->latest.get("id").equals(m.get("view_match_id"))).toList();
        if (remaining.isEmpty()) {
            db.update("UPDATE rooms SET state=?,host_member_id=? WHERE id=?",allRemaining.isEmpty()?"CLOSED":"FINISHED",allRemaining.isEmpty()?null:allRemaining.getFirst().get("id"),id);
        } else if (member.get("id").equals(room.get("host_member_id"))) {
            var next = remaining.stream().filter(this::connected).findFirst().orElse(remaining.getFirst());
            db.update("UPDATE rooms SET host_member_id=?,revision=revision+1 WHERE id=?",next.get("id"),id);
            event(id,"HOST_CHANGED",next.get("username")+" es el nuevo anfitrión.");
        }
        event(id,"MEMBER_LEFT",member.get("username")+" salió de la sala.");
        if(advance) advanceRoom(id);
    }
    private void advanceRoom(String id) {
        for(var match:db.queryForList("SELECT id FROM room_matches WHERE room_id=? AND state IN ('PREPARING','ROUND_ACTIVE','END_VOTE')",id)) matches.advance((String)match.get("id"),(type,text)->event(id,type,text));
    }
    private boolean alive(Map<String,Object> member) {
        return member.get("user_id") != null && accounts.active((String)member.get("user_id"),(String)member.get("session_id"),false)
            && Instant.parse((String)member.get("last_seen_at")).plusSeconds(45).isAfter(clock.instant());
    }
    private boolean connected(Map<String,Object> member) {
        return alive(member) && (member.get("channel_id") == null || ((Number)member.get("channel_connected")).intValue() == 1);
    }
    @Scheduled(initialDelay=5000,fixedDelay=5000)
    public void expireMembers() {
        Database.write(tx, () -> {
            var affected=new LinkedHashSet<String>();
            for (var member : db.queryForList("SELECT * FROM room_members WHERE left_at IS NULL ORDER BY room_id,join_order")) {
                if (!alive(member)) {remove(member,false);affected.add((String)member.get("room_id"));}
            }
            affected.forEach(this::advanceRoom);
            return null;
        });
    }
    private List<Map<String,Object>> publicMembers(List<Map<String,Object>> active) {
        var result = new ArrayList<Map<String,Object>>();
        for (var m : active) {
            var item = new LinkedHashMap<String,Object>();
            item.put("memberId",m.get("id")); item.put("userId",m.get("user_id")); item.put("username",m.get("username"));
            item.put("joinOrder",m.get("join_order")); item.put("ready",((Number)m.get("ready")).intValue() != 0); item.put("connected",connected(m));
            result.add(item);
        }
        return result;
    }
    private Map<String,Object> snapshot(String id) {
        var r = room(id); var room = new LinkedHashMap<String,Object>();var latest=latestMatch(id);
        var visible="LOBBY".equals(r.get("state"))?lobbyMembers(id):latest==null?members(id):members(id).stream().filter(m->latest.get("id").equals(m.get("view_match_id"))).toList();
        for (String field : List.of("id","name","capacity","state","revision")) room.put(field,r.get(field));
        room.put("hostMemberId",r.get("host_member_id")); room.put("levelId",r.get("level_id")); room.put("members",publicMembers(visible));
        var result = new LinkedHashMap<String,Object>();
        result.put("protocolVersion",1); result.put("rulesVersion",1); result.put("roomId",id); result.put("sequence",r.get("sequence"));
        result.put("serverTime",now()); result.put("room",room); result.put("messages",historyRows(id,Long.MAX_VALUE,20)); result.put("match",null);
        var matches = db.queryForList("SELECT * FROM room_matches WHERE room_id=? ORDER BY rowid DESC LIMIT 1",id);
        if (!matches.isEmpty() && !"LOBBY".equals(r.get("state"))) {
            result.put("match",this.matches.publicView(matches.getFirst()));
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> overlay(Map<String,Object> snapshot,Map<String,Object> privateData) {
        var match=(Map<String,Object>)snapshot.get("match");
        if(match!=null) {
            match.put("ownBoard",privateData.getOrDefault("ownBoard",Map.of()));
            match.put("ownPreparationConfirmed",privateData.getOrDefault("ownPreparationConfirmed",false));
            if(match.get("round") instanceof Map<?,?> round) ((Map<String,Object>)round).put("ownAction",privateData.get("ownAction"));
        }
        return snapshot;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> snapshotFor(String id,String user) {
        var snapshot=snapshot(id);var r=room(id);
        var own=db.queryForList("SELECT * FROM room_members WHERE room_id=? AND user_id=? ORDER BY join_order DESC LIMIT 1",id,user);
        if(own.isEmpty()) return snapshot;
        var m=own.getFirst();String view=(String)m.get("view_match_id");
        if(view==null) {snapshot.put("match",null);return snapshot;}
        var selected=matches.match(view);var match=matches.publicView(selected);snapshot.put("match",match);
        var publicRoom=(Map<String,Object>)snapshot.get("room");
        if("FINISHED".equals(selected.get("state"))) {
            publicRoom.put("state","FINISHED");publicRoom.put("levelId",selected.get("level_id"));
            publicRoom.put("members",publicMembers(members(id).stream().filter(p->view.equals(p.get("view_match_id"))).toList()));
            var latest=latestMatch(id);
            boolean available=latest!=null && view.equals(latest.get("id")) && !"CLOSED".equals(r.get("state"));
            match.put("rematch",Map.of("available",available,"acceptedCount",db.queryForObject("SELECT COUNT(*) FROM rematch_acceptances WHERE match_id=?",Integer.class,view)));
        }
        return overlay(snapshot,matches.privateView(view,(String)m.get("id")));
    }
    private Map<String,Object> eventView(String id,long sequence,String user,Map<String,Object> snapshot) {
        var personal=db.queryForList("SELECT e.snapshot_json FROM room_member_events e JOIN room_members m ON m.id=e.member_id WHERE e.room_id=? AND e.sequence=? AND m.user_id=?",id,sequence,user);
        if(!personal.isEmpty()) return decode((String)personal.getFirst().get("snapshot_json"));
        var views=db.queryForList("SELECT v.private_json FROM room_event_views v JOIN room_members m ON m.id=v.member_id WHERE v.room_id=? AND v.sequence=? AND m.user_id=?",id,sequence,user);
        return views.isEmpty()?snapshot:overlay(snapshot,decode((String)views.getFirst().get("private_json")));
    }
    public Map<String,Object> prepare(String matchId,String user,String sid,String cid,JsonNode body,Map<String,Integer> placements) {
        String id=(String)matches.match(matchId).get("room_id");
        return command(user,sid,cid,"prepare",matchId,body,()->{
            var own=member(id,user);matches.prepare(matchId,(String)own.get("id"),placements,(type,text)->event(id,type,text));return id;
        });
    }
    public Map<String,Object> action(String matchId,String roundId,String user,String sid,String cid,JsonNode body,String kind,Integer die,String cell) {
        String id=(String)matches.match(matchId).get("room_id");
        return command(user,sid,cid,"action",matchId+"/"+roundId,body,()->{
            var own=member(id,user);matches.action(matchId,roundId,(String)own.get("id"),kind,die,cell,(type,text)->event(id,type,text));return id;
        });
    }
    public Map<String,Object> rematch(String matchId,String user,String sid,String cid,JsonNode body) {
        String id=(String)matches.match(matchId).get("room_id");
        return command(user,sid,cid,"rematch",matchId,body,()->{
            var r=room(id);var own=member(id,user);var latest=latestMatch(id);
            if(latest==null || !matchId.equals(latest.get("id")) || !"FINISHED".equals(latest.get("state")) || "CLOSED".equals(r.get("state"))) fail(409,"REMATCH_CLOSED");
            if(db.queryForList("SELECT 1 FROM match_players WHERE match_id=? AND member_id=?",matchId,own.get("id")).isEmpty()) fail(403,"NOT_MATCH_PARTICIPANT");
            if(own.get("view_match_id")==null && matchId.equals(r.get("rematch_source_id"))) return id;
            if(!matchId.equals(own.get("view_match_id"))) fail(409,"REMATCH_CLOSED");
            if("LOBBY".equals(r.get("state")) && lobbyMembers(id).size()>=((Number)r.get("capacity")).intValue()) fail(409,"ROOM_FULL");
            if(!"LOBBY".equals(r.get("state"))) db.update("UPDATE rooms SET state='LOBBY',host_member_id=?,level_id=NULL,rematch_source_id=?,revision=revision+1 WHERE id=?",own.get("id"),matchId,id);
            db.update("UPDATE room_members SET view_match_id=NULL,ready=0 WHERE id=?",own.get("id"));
            db.update("INSERT INTO rematch_acceptances VALUES(?,?,?)",matchId,own.get("id"),now());
            event(id,"REMATCH_ACCEPTED",own.get("username")+" aceptó la revancha. Nuevo lobby disponible.");return id;
        });
    }
    public Map<String,Object> vote(String matchId,String user,String sid) {
        advanceMatches();return Database.write(tx,()->{
            accounts.requireActive(user,sid);String id=(String)matches.match(matchId).get("room_id");member(id,user);var response=new LinkedHashMap<String,Object>();response.put("endVote",matches.voteView(matchId));return response;
        });
    }
    public Map<String,Object> ballot(String matchId,String user,String sid,String cid,JsonNode body,String voteId,boolean approve) {
        String id=(String)matches.match(matchId).get("room_id");
        return command(user,sid,cid,"ballot",matchId+"/"+voteId,body,()->{
            var own=member(id,user);matches.ballot(matchId,voteId,(String)own.get("id"),approve,(type,text)->event(id,type,text));return id;
        });
    }
    @Scheduled(initialDelay=250,fixedDelay=250)
    public void advanceMatches() {
        expireMembers();
        Database.write(tx,()->{
            for(var row:db.queryForList("SELECT p.id,p.room_id FROM room_matches p JOIN rooms r ON r.id=p.room_id WHERE r.state<>'CLOSED' AND p.state IN ('PREPARING','ROUND_ACTIVE','END_VOTE')")) {
                String id=(String)row.get("room_id");matches.advance((String)row.get("id"),(type,text)->event(id,type,text));
            }
            return null;
        });
    }
    private void insertMessage(String id, String kind, String author, String name, String text) {
        long sequence = db.queryForObject("SELECT sequence+1 FROM rooms WHERE id=?",Long.class,id);
        db.update("INSERT INTO room_messages VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,sequence,kind,author,name,text,now());
    }
    private List<Map<String,Object>> historyRows(String id, long before, int limit) {
        var rows = db.queryForList("SELECT * FROM room_messages WHERE room_id=? AND sequence<? ORDER BY sequence DESC LIMIT ?",id,before,limit);
        Collections.reverse(rows);
        var result = new ArrayList<Map<String,Object>>();
        for (var row : rows) {
            var message = new LinkedHashMap<String,Object>();
            message.put("id",row.get("id")); message.put("sequence",row.get("sequence")); message.put("kind",row.get("kind"));
            message.put("authorMemberId",row.get("author_member_id")); message.put("authorName",row.get("author_name"));
            message.put("text",row.get("text")); message.put("serverTime",row.get("server_time")); result.add(message);
        }
        return result;
    }
    public Map<String,Object> messages(String id, String user, String sid, Long before) {
        if (before != null && before < 1) fail(400,"VALIDATION_ERROR");
        return Database.write(tx,() -> {
            accounts.requireActive(user,sid); room(id); member(id,user);
            var messages = historyRows(id,before == null ? Long.MAX_VALUE : before,20);
            var result = new LinkedHashMap<String,Object>(); result.put("messages",messages);
            result.put("nextBefore",messages.isEmpty() ? null : messages.getFirst().get("sequence"));
            return result;
        });
    }
    public Map<String,Object> message(String id,String user,String sid,String cid,JsonNode body,String text) {
        if (text == null || text.strip().isEmpty() || text.codePointCount(0,text.length()) > 300
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) fail(400,"VALIDATION_ERROR");
        return command(user,sid,cid,"message",id,body,() -> {
            var r = room(id); var m = member(id,user);
            if ("CLOSED".equals(r.get("state"))) fail(403,"NOT_ROOM_MEMBER");
            limits.hit("chat:"+user,10,Duration.ofSeconds(10));
            insertMessage(id,"PLAYER",(String)m.get("id"),(String)m.get("username"),text.strip());
            event(id,"MESSAGE_CREATED"); return id;
        });
    }
    public Map<String,Object> authorizeChannel(String id,String user,String sid) {
        expireMembers();
        return Database.write(tx,() -> { accounts.requireActive(user,sid); room(id); return member(id,user); });
    }
    public Map<String,Object> bindChannel(String id,String user,String sid,String channelId) {
        expireMembers();
        return Database.write(tx,() -> {
            accounts.requireActive(user,sid); room(id); var m = member(id,user);
            if (!sid.equals(m.get("session_id"))) fail(403,"NOT_ROOM_MEMBER");
            boolean reconnecting = m.get("channel_id") != null && !connected(m);
            db.update("UPDATE room_members SET channel_id=?,channel_connected=1,last_seen_at=? WHERE id=?",channelId,now(),m.get("id"));
            if (reconnecting) event(id,"MEMBER_CONNECTION_CHANGED");
            return snapshotFor(id,user);
        });
    }
    public void disconnectChannel(String id,String user,String channelId) {
        Database.write(tx,() -> {
            int count = db.update("UPDATE room_members SET channel_connected=0 WHERE room_id=? AND user_id=? AND channel_id=? AND channel_connected=1 AND left_at IS NULL",id,user,channelId);
            if (count > 0) event(id,"MEMBER_CONNECTION_CHANGED");
            return null;
        });
    }
    private void requireChannel(String id,String user,String sid,String channelId) {
        accounts.requireActive(user,sid); var m = member(id,user);
        if (!channelId.equals(m.get("channel_id"))) fail(409,"CHANNEL_REPLACED");
        if (!alive(m)) fail(403,"NOT_ROOM_MEMBER");
    }
    public void channelPing(String id,String user,String sid,String channelId) {
        Database.write(tx,() -> {
            requireChannel(id,user,sid,channelId);
            db.update("UPDATE room_members SET last_seen_at=? WHERE room_id=? AND user_id=? AND channel_id=? AND left_at IS NULL",now(),id,user,channelId);
            return null;
        });
    }
    /** Reads only committed rows. Never performs network I/O while SQLite holds a transaction. */
    public List<Map<String,Object>> channelEvents(String id,String user,String sid,String channelId,long after) {
        return Database.write(tx,() -> {
            requireChannel(id,user,sid,channelId);
            var result = new ArrayList<Map<String,Object>>();
            for (var row : db.queryForList("SELECT * FROM room_events WHERE room_id=? AND sequence>? ORDER BY sequence LIMIT 32",id,after)) {
                result.add(Map.of("protocolVersion",1,"roomId",id,"sequence",row.get("sequence"),"type",row.get("type"),"serverTime",row.get("server_time"),"data",eventView(id,((Number)row.get("sequence")).longValue(),user,decode((String)row.get("data_json")))));
            }
            return result;
        });
    }
    private void event(String id, String type) { event(id,type,null); }
    private void event(String id,String type,String systemText) {
        String system = systemText!=null ? systemText : switch(type) {
            case "MEMBER_JOINED" -> "Un participante se unió a la sala.";
            case "MEMBER_LEFT" -> "Un participante salió de la sala.";
            case "HOST_CHANGED" -> "La sala tiene un nuevo anfitrión.";
            case "MATCH_STARTED" -> "Comienza la preparación de la partida.";
            default -> null;
        };
        if (system != null) insertMessage(id,"SYSTEM",null,null,system);
        db.update("UPDATE rooms SET sequence=sequence+1 WHERE id=?",id);
        var snapshot = snapshot(id);
        db.update("INSERT INTO room_events VALUES(?,?,?,?,?)",id,snapshot.get("sequence"),type,now(),encode(snapshot));
        for(var m:members(id)) {
            if(m.get("user_id")!=null) db.update("INSERT INTO room_member_events VALUES(?,?,?,?)",id,snapshot.get("sequence"),m.get("id"),encode(snapshotFor(id,(String)m.get("user_id"))));
        }
        var match=(Map<?,?>)snapshot.get("match");
        if(match!=null) {
            for(var player:db.queryForList("SELECT member_id FROM match_players WHERE match_id=?",match.get("id"))) {
                String member=(String)player.get("member_id");
                db.update("INSERT INTO room_event_views VALUES(?,?,?,?)",id,snapshot.get("sequence"),member,encode(matches.privateView((String)match.get("id"),member)));
            }
        }
    }
}
