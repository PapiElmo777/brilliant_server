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
    private final SecureRandom random = new SecureRandom();
    private final BoardCatalog catalog = new BoardCatalog();
    public RoomService(JdbcTemplate db, TransactionTemplate tx, Accounts accounts, Clock clock, ObjectMapper json) {
        this.db = db; this.tx = tx; this.accounts = accounts; this.clock = clock; this.json = json;
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
        return AuthService.sha256(operation + "\n" + resource + "\n" + encode(sorted));
    }
    /** BEGIN IMMEDIATE serializes the last slot, start/join and replay receipt atomically. */
    private Map<String,Object> command(String user, String sid, String commandId, String operation, String resource, JsonNode body, Supplier<String> effect) {
        uuid(commandId);
        expireMembers();
        return Database.write(tx, () -> {
            accounts.requireActive(user,sid);
            String fingerprint = fingerprint(operation,resource,body);
            var prior = db.queryForList("SELECT * FROM room_commands WHERE user_id=? AND command_id=?",user,commandId);
            if (!prior.isEmpty()) {
                if (!fingerprint.equals(prior.getFirst().get("fingerprint"))) fail(409,"COMMAND_CONFLICT");
                return decode((String)prior.getFirst().get("response_json"));
            }
            String id = effect.get();
            var snapshot = snapshot(id);
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
                var active = members((String)r.get("id"));
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
            db.update("UPDATE room_members SET last_seen_at=? WHERE id=?",now(),member.get("id"));
            return snapshot(id);
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
            event(id,"MEMBER_JOINED"); return id;
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
            if (members(id).size() >= ((Number)room.get("capacity")).intValue()) fail(409,"ROOM_FULL");
            addMember(id,user,sid); event(id,"MEMBER_JOINED"); return id;
        });
    }
    public Map<String,Object> settings(String id, String user, String sid, String cid, JsonNode body, Long expected, Integer capacity, String level, boolean chooseRandom) {
        return command(user,sid,cid,chooseRandom ? "random-level" : "settings",id,body,() -> {
            var room = room(id); var member = member(id,user); host(room,member); lobby(room); revision(room,expected);
            int cap = chooseRandom ? ((Number)room.get("capacity")).intValue() : capacity == null ? 0 : capacity;
            if (cap < 2 || cap > 6) fail(400,"VALIDATION_ERROR");
            if (cap < members(id).size()) fail(409,"CAPACITY_BELOW_OCCUPANCY");
            String selected = chooseRandom ? catalog.catalog().boards().get(random.nextInt(4)).id() : level;
            if (selected != null && catalog.catalog().boards().stream().noneMatch(b -> b.id().equals(selected))) fail(400,"VALIDATION_ERROR");
            db.update("UPDATE rooms SET capacity=?,level_id=?,revision=revision+1 WHERE id=?",cap,selected,id);
            event(id,"SETTINGS_CHANGED"); return id;
        });
    }
    public Map<String,Object> ready(String id, String user, String sid, String cid, JsonNode body, Long expected, Boolean ready) {
        if (ready == null) fail(400,"VALIDATION_ERROR");
        return command(user,sid,cid,"ready",id,body,() -> {
            var room = room(id); var member = member(id,user); lobby(room); revision(room,expected);
            if (member.get("id").equals(room.get("host_member_id"))) fail(409,"HOST_STARTS_MATCH");
            db.update("UPDATE room_members SET ready=?,last_seen_at=? WHERE id=?",ready ? 1 : 0,now(),member.get("id"));
            event(id,"READY_CHANGED"); return id;
        });
    }
    public Map<String,Object> start(String id, String user, String sid, String cid, JsonNode body, Long expected) {
        return command(user,sid,cid,"start",id,body,() -> {
            var room = room(id); host(room,member(id,user)); lobby(room); revision(room,expected);
            var active = members(id);
            if (active.size() < 2) fail(409,"NOT_ENOUGH_PLAYERS");
            if (room.get("level_id") == null) fail(409,"LEVEL_REQUIRED");
            if (active.stream().anyMatch(m -> !connected(m) || (!m.get("id").equals(room.get("host_member_id")) && ((Number)m.get("ready")).intValue() == 0)))
                fail(409,"PLAYERS_NOT_READY");
            String matchId = UUID.randomUUID().toString();
            db.update("INSERT INTO room_matches VALUES(?,?,?,?,?,?,?,?)",matchId,id,room.get("level_id"),1,Scoring.target(active.size()),"PREPARING",encode(publicMembers(active)),now());
            db.update("UPDATE rooms SET state='PREPARING' WHERE id=?",id);
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
    private void remove(Map<String,Object> member) {
        String id = (String)member.get("room_id");
        if (db.update("UPDATE room_members SET left_at=?,ready=0 WHERE id=? AND left_at IS NULL",now(),member.get("id")) == 0) return;
        var room = room(id); var remaining = members(id);
        if (remaining.isEmpty()) {
            db.update("UPDATE rooms SET state='CLOSED',host_member_id=NULL WHERE id=?",id);
        } else if (member.get("id").equals(room.get("host_member_id"))) {
            var next = remaining.stream().filter(this::connected).findFirst().orElse(remaining.getFirst());
            db.update("UPDATE rooms SET host_member_id=?,revision=revision+1 WHERE id=?",next.get("id"),id);
            event(id,"HOST_CHANGED");
        }
        event(id,"MEMBER_LEFT");
    }
    private boolean connected(Map<String,Object> member) {
        return member.get("user_id") != null && accounts.active((String)member.get("user_id"),(String)member.get("session_id"),false)
            && Instant.parse((String)member.get("last_seen_at")).plusSeconds(45).isAfter(clock.instant());
    }
    @Scheduled(initialDelay=5000,fixedDelay=5000)
    public void expireMembers() {
        Database.write(tx, () -> {
            for (var member : db.queryForList("SELECT * FROM room_members WHERE left_at IS NULL ORDER BY room_id,join_order")) {
                if (!connected(member)) remove(member);
            }
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
        var r = room(id); var room = new LinkedHashMap<String,Object>();
        for (String field : List.of("id","name","capacity","state","revision")) room.put(field,r.get(field));
        room.put("hostMemberId",r.get("host_member_id")); room.put("levelId",r.get("level_id")); room.put("members",publicMembers(members(id)));
        var result = new LinkedHashMap<String,Object>();
        result.put("protocolVersion",1); result.put("rulesVersion",1); result.put("roomId",id); result.put("sequence",r.get("sequence"));
        result.put("serverTime",now()); result.put("room",room); result.put("messages",List.of()); result.put("match",null);
        var matches = db.queryForList("SELECT * FROM room_matches WHERE room_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1",id);
        if (!matches.isEmpty()) {
            var m = matches.getFirst(); var match = new LinkedHashMap<String,Object>();
            match.put("id",m.get("id")); match.put("state",m.get("state")); match.put("levelId",m.get("level_id")); match.put("target",m.get("target"));
            try { match.put("participants",json.readTree((String)m.get("participants_json"))); }
            catch(Exception e) { throw new IllegalStateException(e); }
            match.put("ownBoard",Map.of()); match.put("round",null); match.put("result",null); result.put("match",match);
        }
        return result;
    }
    private void event(String id, String type) {
        db.update("UPDATE rooms SET sequence=sequence+1 WHERE id=?",id);
        var snapshot = snapshot(id);
        db.update("INSERT INTO room_events VALUES(?,?,?,?,?)",id,snapshot.get("sequence"),type,now(),encode(snapshot));
    }
}
