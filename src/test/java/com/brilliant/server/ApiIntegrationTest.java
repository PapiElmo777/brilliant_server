package com.brilliant.server;

import com.brilliant.server.auth.*;
import com.brilliant.server.users.Accounts;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {
    static final Path DATA;
    static { try { DATA=Files.createTempDirectory("brilliant-api-"); } catch(Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("app.data-dir",DATA::toString);
        r.add("app.register-ip-limit",()->10000); r.add("app.login-ip-limit",()->10000);
        r.add("app.account-limit",()->10000);
        r.add("app.record-test-accounts",()->true);
    }
    static class TestClock extends Clock {
        volatile Instant now=Instant.parse("2026-11-02T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary TestClock testClock() { return new TestClock(); } }
    @Autowired TestClock clock;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @LocalServerPort int port;
    final HttpClient client=HttpClient.newHttpClient();
    static final String PASSWORD="Mi clave espacial segura 🚀 47";
    record Reply(int status,JsonNode body,HttpHeaders headers) {}
    @Test void shortPasswordRequiresSpecialCharacterAndSupportsAccountLifecycle() throws Exception {
        for(String invalid : List.of("a!", "abc", "a 2", "áéí")) {
            error(request("POST","/auth/register",Map.of("username",name(),"password",invalid),null),400,"VALIDATION_ERROR");
        }
        String name=name();
        Reply registered=request("POST","/auth/register",Map.of("username",name,"password","a!2"),null);
        assertEquals(201,registered.status);
        assertEquals(204,request("POST","/auth/logout",null,access(registered)).status);
        Reply login=request("POST","/auth/login",Map.of("username",name,"password","a!2"),null);
        assertEquals(200,login.status);
        assertEquals(204,request("DELETE","/users/me",Map.of("currentPassword","a!2"),access(login)).status);
        error(request("POST","/auth/login",Map.of("username",name,"password","a!2"),null),401,"INVALID_CREDENTIALS");
    }
    @Test void existingPasswordWithoutSymbolStillAllowsLoginAndDeletion() throws Exception {
        String username=name(), password="Clave anterior sin simbolos 8492";
        String id=UUID.randomUUID().toString();
        // Seed a legacy account through the same password encoder used in production.
        var encoder=org.springframework.security.crypto.argon2.Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        db.update("INSERT INTO users(id,username,username_normalized,password_hash,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            id,username,username.toLowerCase(Locale.ROOT),encoder.encode(password),clock.instant().toString(),clock.instant().toString());
        Reply login=request("POST","/auth/login",Map.of("username",username,"password",password),null);
        assertEquals(200,login.status);
        assertEquals(204,request("DELETE","/users/me",Map.of("currentPassword",password),access(login)).status);
    }
    @Test void fourClientsCanRegisterLoginLogoutAndDeleteIndependently() throws Exception {
        try (var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<4;i++) futures.add(pool.submit(() -> {
                String username=name();
                Reply registered=register(username);
                assertEquals(201,registered.status);
                assertEquals(204,request("POST","/auth/logout",null,access(registered)).status);
                Reply logged=request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null);
                assertEquals(200,logged.status);
                assertEquals(204,request("POST","/auth/logout",null,access(logged)).status);
                error(request("GET","/users/me",null,access(logged)),401,"SESSION_INVALID");
                error(request("GET","/users/me",null,access(registered)),401,"SESSION_INVALID");
                Reply again=request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null);
                assertEquals(200,again.status);
                assertEquals(204,request("DELETE","/users/me",Map.of("currentPassword",PASSWORD),access(again)).status);
                return registered.body.at("/user/id").asText();
            }));
            var users=new HashSet<String>();
            for(var future:futures) users.add(future.get(30,TimeUnit.SECONDS));
            assertEquals(4,users.size());
        }
    }
    @Test void activeSessionRotatesBeyondTenMinutesWithoutChangingSessionIdentity() throws Exception {
        String username = name();
        Reply current = register(username);
        String userId = current.body.at("/user/id").asText();
        String sessionId = db.queryForObject("SELECT id FROM sessions WHERE user_id=? AND revoked_at IS NULL", String.class, userId);
        for (int second = 10; second <= 1260; second += 10) {
            clock.now = clock.now.plusSeconds(10);
            assertEquals(200, request("GET", "/users/me", null, access(current)).status);
            if (second % 540 == 0) {
                Reply rotated = request("POST", "/auth/refresh", Map.of("refreshToken", refresh(current)), null);
                assertEquals(200, rotated.status);
                assertNotEquals(refresh(current), refresh(rotated));
                assertEquals(userId, rotated.body.at("/user/id").asText());
                current = rotated;
                error(request("POST", "/auth/login", Map.of("username", username, "password", PASSWORD), null), 409, "SESSION_ALREADY_ACTIVE");
            }
        }
        assertEquals(sessionId, db.queryForObject("SELECT id FROM sessions WHERE user_id=? AND revoked_at IS NULL", String.class, userId));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sessions WHERE user_id=? AND revoked_at IS NULL", Integer.class, userId));
        assertEquals(204, request("DELETE", "/users/me", Map.of("currentPassword", PASSWORD), access(current)).status);
    }
    Reply roomCommand(String method, String route, Reply account, Map<String,Object> fields) throws Exception {
        var body = new LinkedHashMap<String,Object>(fields);
        body.put("commandId",UUID.randomUUID().toString());
        return request(method,route,body,access(account));
    }
    String roomId(Reply result) { assertEquals(200,result.status); return result.body.path("roomId").asText(); }
    long revision(Reply snapshot) { return snapshot.body.at("/room/revision").asLong(); }
    @Test void sixClientsShareLobbyAndSeventhCannotEnterOrConfigure() throws Exception {
        var clients = new ArrayList<Reply>();
        for (int i=0;i<7;i++) clients.add(register());
        String id = roomId(roomCommand("POST","/rooms",clients.getFirst(),Map.of("name","Escuadrón","capacity",6)));
        for (int i=1;i<6;i++) assertEquals(200,roomCommand("POST","/rooms/"+id+"/join",clients.get(i),Map.of()).status);
        error(roomCommand("POST","/rooms/"+id+"/join",clients.get(6),Map.of()),409,"ROOM_FULL");
        error(request("GET","/rooms/"+id,null,access(clients.get(6))),403,"NOT_ROOM_MEMBER");
        error(roomCommand("PATCH","/rooms/"+id+"/settings",clients.get(1),Map.of("expectedRevision",0,"capacity",6,"levelId","tablero_02")),403,"HOST_ONLY");
        var selected = roomCommand("PATCH","/rooms/"+id+"/settings",clients.getFirst(),Map.of("expectedRevision",0,"capacity",6,"levelId","tablero_02"));
        assertEquals(200,selected.status);
        for (var client : clients.subList(0,6)) {
            var snapshot = request("GET","/rooms/"+id,null,access(client));
            assertEquals(200,snapshot.status);
            assertEquals(selected.body.path("data"),snapshot.body); // Fixed test clock: same committed state.
            assertEquals(6,snapshot.body.at("/room/members").size());
        }
        for (int i=1;i<6;i++) assertEquals(200,roomCommand("PUT","/rooms/"+id+"/ready",clients.get(i),Map.of("expectedRevision",1,"ready",true)).status);
        var started = roomCommand("POST","/rooms/"+id+"/start",clients.getFirst(),Map.of("expectedRevision",1));
        assertEquals(200,started.status); assertEquals(20,started.body.at("/data/match/target").asInt());
        assertEquals(6,started.body.at("/data/match/participants").size());
        error(roomCommand("POST","/rooms/"+id+"/join",clients.get(6),Map.of()),409,"ROOM_ALREADY_STARTED");
    }
    @Test void commandsAreIdempotentAndMembershipIsUniqueAcrossRooms() throws Exception {
        Reply owner = register(); String cid = UUID.randomUUID().toString();
        var body = Map.of("commandId",cid,"name","Servidor de prueba","capacity",6);
        Reply created = request("POST","/rooms",body,access(owner));
        String id = roomId(created);
        assertEquals(created.body,request("POST","/rooms",body,access(owner)).body);
        error(request("POST","/rooms",Map.of("commandId",cid,"name","Otro","capacity",6),access(owner)),409,"COMMAND_CONFLICT");
        error(roomCommand("POST","/rooms",owner,Map.of("name","Otra sala","capacity",2)),409,"ALREADY_IN_ROOM");
        Reply other = register(); String second = roomId(roomCommand("POST","/rooms",other,Map.of("name","Segunda","capacity",2)));
        error(roomCommand("POST","/rooms/"+second+"/join",owner,Map.of()),409,"ALREADY_IN_ROOM");
        assertEquals(id,request("GET","/rooms",null,access(owner)).body.path("currentRoomId").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM room_members WHERE user_id=? AND left_at IS NULL",Integer.class,owner.body.at("/user/id").asText()));
        Reply rotated = request("POST","/auth/refresh",Map.of("refreshToken",refresh(owner)),null);
        assertEquals(200,request("GET","/rooms/"+id,null,access(rotated)).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM room_members WHERE room_id=?",Integer.class,id));
    }
    @Test void lastSlotRaceNeverExceedsCapacity() throws Exception {
        Reply host=register(),a=register(),b=register();
        String id=roomId(roomCommand("POST","/rooms",host,Map.of("name","Último lugar","capacity",2)));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=pool.invokeAll(List.<Callable<Reply>>of(
                ()->roomCommand("POST","/rooms/"+id+"/join",a,Map.of()),
                ()->roomCommand("POST","/rooms/"+id+"/join",b,Map.of())));
            assertEquals(List.of(200,409),results.stream().map(f->{try{return f.get().status;}catch(Exception e){throw new RuntimeException(e);}}).sorted().toList());
        }
        assertEquals(2,request("GET","/rooms/"+id,null,access(host)).body.at("/room/members").size());
    }
    @Test void readySurvivesConfigurationChangesAndRandomRetryDoesNotReroll() throws Exception {
        Reply host=register(),guest=register();
        String id=roomId(roomCommand("POST","/rooms",host,Map.of("name","Listos","capacity",6)));
        roomCommand("POST","/rooms/"+id+"/join",guest,Map.of());
        error(roomCommand("POST","/rooms/"+id+"/start",host,Map.of("expectedRevision",0)),409,"LEVEL_REQUIRED");
        roomCommand("PUT","/rooms/"+id+"/ready",guest,Map.of("expectedRevision",0,"ready",true));
        var cid=UUID.randomUUID().toString();var randomBody=Map.of("commandId",cid,"expectedRevision",0);
        Reply random=request("POST","/rooms/"+id+"/random-level",randomBody,access(host));
        assertEquals(random.body,request("POST","/rooms/"+id+"/random-level",randomBody,access(host)).body);
        assertTrue(random.body.at("/data/room/members/1/ready").asBoolean());
        var changed=roomCommand("PATCH","/rooms/"+id+"/settings",host,Map.of("expectedRevision",1,"capacity",2,"levelId","tablero_04"));
        assertTrue(changed.body.at("/data/room/members/1/ready").asBoolean());
        error(roomCommand("PUT","/rooms/"+id+"/ready",guest,Map.of("expectedRevision",1,"ready",false)),409,"STALE_SETTINGS");
        assertEquals(200,roomCommand("PUT","/rooms/"+id+"/ready",guest,Map.of("expectedRevision",2,"ready",false)).status);
        error(roomCommand("POST","/rooms/"+id+"/start",host,Map.of("expectedRevision",2)),409,"PLAYERS_NOT_READY");
        roomCommand("PUT","/rooms/"+id+"/ready",guest,Map.of("expectedRevision",2,"ready",true));
        var started=roomCommand("POST","/rooms/"+id+"/start",host,Map.of("expectedRevision",2));
        assertEquals(26,started.body.at("/data/match/target").asInt());
        assertEquals("tablero_04",started.body.at("/data/match/levelId").asText());
        error(roomCommand("PATCH","/rooms/"+id+"/settings",host,Map.of("expectedRevision",2,"capacity",6,"levelId","tablero_01")),409,"ROOM_ALREADY_STARTED");
    }
    @Test void capacitySixStartsWithTwoAndCommandRetryKeepsSameMatchAndTarget() throws Exception {
        Reply host=register(),guest=register();
        String id=roomId(roomCommand("POST","/rooms",host,Map.of("name","Dos de seis","capacity",6)));
        roomCommand("POST","/rooms/"+id+"/join",guest,Map.of());
        var changed=roomCommand("PATCH","/rooms/"+id+"/settings",host,Map.of("expectedRevision",0,"capacity",5,"levelId",json.nullNode()));
        assertEquals(200,changed.status);assertTrue(changed.body.at("/data/room/levelId").isNull());
        roomCommand("PATCH","/rooms/"+id+"/settings",host,Map.of("expectedRevision",1,"capacity",6,"levelId","tablero_03"));
        roomCommand("PUT","/rooms/"+id+"/ready",guest,Map.of("expectedRevision",2,"ready",true));
        var body=Map.of("commandId",UUID.randomUUID().toString(),"expectedRevision",2);
        var started=request("POST","/rooms/"+id+"/start",body,access(host));
        assertEquals(200,started.status);assertEquals(26,started.body.at("/data/match/target").asInt());
        assertEquals(2,started.body.at("/data/match/participants").size());
        assertEquals(started.body,request("POST","/rooms/"+id+"/start",body,access(host)).body);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM room_matches WHERE room_id=?",Integer.class,id));
        roomCommand("POST","/rooms/"+id+"/leave",guest,Map.of());
        assertEquals(26,request("GET","/rooms/"+id,null,access(host)).body.at("/match/target").asInt());
    }
    @Test void startJoinRaceHasOneFrozenValidRoster() throws Exception {
        Reply host=register(),guest=register(),newcomer=register();
        String id=roomId(roomCommand("POST","/rooms",host,Map.of("name","Carrera","capacity",6)));
        roomCommand("POST","/rooms/"+id+"/join",guest,Map.of());
        roomCommand("PATCH","/rooms/"+id+"/settings",host,Map.of("expectedRevision",0,"capacity",6,"levelId","tablero_01"));
        roomCommand("PUT","/rooms/"+id+"/ready",guest,Map.of("expectedRevision",1,"ready",true));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=pool.submit(()->roomCommand("POST","/rooms/"+id+"/start",host,Map.of("expectedRevision",1)));
            var join=pool.submit(()->roomCommand("POST","/rooms/"+id+"/join",newcomer,Map.of()));
            var s=start.get();var j=join.get();
            if(s.status==200) {
                assertEquals(409,j.status);assertEquals(2,s.body.at("/data/match/participants").size());
                assertEquals(26,s.body.at("/data/match/target").asInt());
            } else { error(s,409,"PLAYERS_NOT_READY");assertEquals(200,j.status); }
        }
    }
    @Test void leavingLogoutAndDeletionTransferHostAndPreserveHistory() throws Exception {
        Reply host=register(),guest=register(),third=register();
        String id=roomId(roomCommand("POST","/rooms",host,Map.of("name","Continuidad","capacity",3)));
        roomCommand("POST","/rooms/"+id+"/join",guest,Map.of());roomCommand("POST","/rooms/"+id+"/join",third,Map.of());
        error(roomCommand("PATCH","/rooms/"+id+"/settings",host,Map.of("expectedRevision",0,"capacity",2,"levelId","tablero_01")),409,"CAPACITY_BELOW_OCCUPANCY");
        assertEquals(204,request("POST","/auth/logout",null,access(host)).status);
        var snapshot=request("GET","/rooms/"+id,null,access(guest));
        assertEquals(snapshot.body.at("/room/members/0/memberId"),snapshot.body.at("/room/hostMemberId"));
        assertEquals(204,request("DELETE","/users/me",Map.of("currentPassword",PASSWORD),access(guest)).status);
        snapshot=request("GET","/rooms/"+id,null,access(third));
        assertEquals(1,snapshot.body.at("/room/members").size());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM room_members WHERE room_id=?",Integer.class,id));
        assertEquals(200,roomCommand("POST","/rooms/"+id+"/leave",third,Map.of()).status);
        assertEquals("CLOSED",db.queryForObject("SELECT state FROM rooms WHERE id=?",String.class,id));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM room_events WHERE room_id=?",Integer.class,id)>=6);
    }
    @Test void roomPresenceExpiresIndependentlyFromAccountHeartbeat() throws Exception {
        Reply host=register(),guest=register();
        String id=roomId(roomCommand("POST","/rooms",host,Map.of("name","Presencia","capacity",2)));
        roomCommand("POST","/rooms/"+id+"/join",guest,Map.of());
        clock.now=clock.now.plusSeconds(30);
        request("GET","/users/me",null,access(host));request("GET","/users/me",null,access(guest));
        assertEquals(200,request("GET","/rooms/"+id,null,access(guest)).status);
        clock.now=clock.now.plusSeconds(15);
        var snapshot=request("GET","/rooms/"+id,null,access(guest));
        assertEquals(1,snapshot.body.at("/room/members").size());
        error(request("GET","/rooms/"+id,null,access(host)),403,"NOT_ROOM_MEMBER");
    }
    @Test void malformedRoomCommandsCannotImpersonateMembers() throws Exception {
        Reply host=register();
        error(request("POST","/rooms",Map.of("commandId",UUID.randomUUID().toString(),"name","Sala","capacity",2,"userId","otro"),access(host)),400,"VALIDATION_ERROR");
        error(request("POST","/rooms",Map.of("commandId",UUID.randomUUID().toString(),"name","Sala","capacity",2.5),access(host)),400,"VALIDATION_ERROR");
        error(request("POST","/rooms",Map.of("commandId","invalido","name","Sala","capacity",2),access(host)),400,"VALIDATION_ERROR");
        error(request("GET","/rooms",null,null),401,"UNAUTHORIZED");
    }
    final class ChannelProbe implements WebSocket.Listener,AutoCloseable {
        final BlockingQueue<JsonNode> frames=new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed=new CompletableFuture<>();
        final StringBuilder text=new StringBuilder();
        WebSocket socket;
        public void onOpen(WebSocket ws) {socket=ws;ws.request(1);}
        public CompletionStage<?> onText(WebSocket ws,CharSequence part,boolean last) {
            text.append(part);if(last) {try{frames.add(json.readTree(text.toString()));text.setLength(0);}catch(Exception e){throw new RuntimeException(e);}}
            ws.request(1);return null;
        }
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason) {closed.complete(code);return null;}
        public void onError(WebSocket ws,Throwable error) {closed.completeExceptionally(error);}
        JsonNode next(String type) throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<until) {
                var frame=frames.poll(200,TimeUnit.MILLISECONDS);
                if(frame!=null && type.equals(frame.path("type").asText())) return frame;
            }
            fail("No llegó el evento "+type);return null;
        }
        public void close() {if(socket!=null) {try{socket.sendClose(1000,"").get(2,TimeUnit.SECONDS);}catch(Exception e){socket.abort();}}}
    }
    ChannelProbe channel(Reply account,String id) {
        var probe=new ChannelProbe();
        probe.socket=client.newWebSocketBuilder().header("Authorization","Bearer "+access(account))
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/v1/rooms/"+id+"/events"),probe).join();
        return probe;
    }
    @Test void socketReceivesCommittedChatOnlyOnceAndRestoresIdentityOnReconnect() throws Exception {
        Reply host=register(),guest=register();
        String id=roomId(roomCommand("POST","/rooms",host,Map.of("name","Canal","capacity",6)));
        roomCommand("POST","/rooms/"+id+"/join",guest,Map.of());
        try(var a=channel(host,id);var b=channel(guest,id)) {
            var initial=a.next("SNAPSHOT");b.next("SNAPSHOT");
            String memberId=initial.at("/data/room/members/1/memberId").asText();
            var body=Map.of("commandId",UUID.randomUUID().toString(),"text","Hola 🚀 <b>texto plano</b>");
            var accepted=request("POST","/rooms/"+id+"/messages",body,access(guest));assertEquals(200,accepted.status);
            var received=a.next("MESSAGE_CREATED");var own=b.next("MESSAGE_CREATED");
            assertEquals(accepted.body.path("sequence"),received.path("sequence"));
            assertEquals(received.path("sequence"),own.path("sequence"));
            var messages=received.at("/data/messages");var last=messages.get(messages.size()-1);
            assertEquals("PLAYER",last.path("kind").asText());assertEquals(memberId,last.path("authorMemberId").asText());
            assertEquals("Hola 🚀 <b>texto plano</b>",last.path("text").asText());
            assertEquals(accepted.body,request("POST","/rooms/"+id+"/messages",body,access(guest)).body);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM room_messages WHERE room_id=? AND kind='PLAYER'",Integer.class,id));
            b.close();
            // GET cannot conceal a lost channel; reconnect retains the same participant.
            clock.now=clock.now.plusSeconds(20);
            request("GET","/users/me",null,access(host));request("GET","/users/me",null,access(guest));
            try(var reconnected=channel(guest,id)) {
                var restored=reconnected.next("SNAPSHOT");
                assertEquals(memberId,restored.at("/data/room/members/1/memberId").asText());
                assertTrue(restored.at("/data/room/members/1/connected").asBoolean());
                assertEquals(last.path("id"),restored.at("/data/messages").get(restored.at("/data/messages").size()-1).path("id"));
            }
        }
    }
    @Test void channelRejectsOtherRoomsQueriesAndUnauthorizedHandshakes() throws Exception {
        Reply owner=register(),other=register();String id=roomId(roomCommand("POST","/rooms",owner,Map.of("name","Privada","capacity",2)));
        var ex=assertThrows(CompletionException.class,()->channel(other,id));
        assertEquals(403,((WebSocketHandshakeException)ex.getCause()).getResponse().statusCode());
        var query=assertThrows(CompletionException.class,()->client.newWebSocketBuilder().header("Authorization","Bearer "+access(owner))
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/v1/rooms/"+id+"/events?token=prohibido"),new ChannelProbe()).join());
        assertEquals(400,((WebSocketHandshakeException)query.getCause()).getResponse().statusCode());
        var noAuth=assertThrows(CompletionException.class,()->client.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/v1/rooms/"+id+"/events"),new ChannelProbe()).join());
        assertEquals(401,((WebSocketHandshakeException)noAuth.getCause()).getResponse().statusCode());
        error(roomCommand("POST","/rooms/"+id+"/messages",other,Map.of("text","Intruso")),403,"NOT_ROOM_MEMBER");
        error(request("GET","/rooms/"+id+"/messages",null,access(other)),403,"NOT_ROOM_MEMBER");
    }
    @Test void chatHistoryPaginationRateLimitAndSystemForgeryAreControlled() throws Exception {
        Reply owner=register();String id=roomId(roomCommand("POST","/rooms",owner,Map.of("name","Historial","capacity",2)));
        long sequence=db.queryForObject("SELECT sequence FROM rooms WHERE id=?",Long.class,id);
        error(roomCommand("POST","/rooms/"+id+"/messages",owner,Map.of("text","Soy sistema","kind","SYSTEM")),400,"VALIDATION_ERROR");
        error(roomCommand("POST","/rooms/"+id+"/messages",owner,Map.of("text","x".repeat(301))),400,"VALIDATION_ERROR");
        assertEquals(sequence,db.queryForObject("SELECT sequence FROM rooms WHERE id=?",Long.class,id));
        for(int i=0;i<10;i++) assertEquals(200,roomCommand("POST","/rooms/"+id+"/messages",owner,Map.of("text","Mensaje "+i)).status);
        var limited=roomCommand("POST","/rooms/"+id+"/messages",owner,Map.of("text","Demasiados"));error(limited,429,"RATE_LIMITED");
        assertTrue(Integer.parseInt(limited.headers.firstValue("retry-after").orElseThrow())>0);
        clock.now=clock.now.plusSeconds(11);request("GET","/users/me",null,access(owner));
        for(int i=10;i<20;i++) assertEquals(200,roomCommand("POST","/rooms/"+id+"/messages",owner,Map.of("text","Mensaje "+i)).status);
        var page=request("GET","/rooms/"+id+"/messages",null,access(owner));assertEquals(20,page.body.path("messages").size());
        var older=request("GET","/rooms/"+id+"/messages?before="+page.body.path("nextBefore").asLong(),null,access(owner));
        assertEquals(1,older.body.path("messages").size());assertEquals("SYSTEM",older.body.at("/messages/0/kind").asText());
    }
    @Test void channelReplacementAndSessionRevocationCloseOldSockets() throws Exception {
        Reply owner=register();String id=roomId(roomCommand("POST","/rooms",owner,Map.of("name","Renovación","capacity",2)));
        try(var old=channel(owner,id)) {
            old.next("SNAPSHOT");var rotated=request("POST","/auth/refresh",Map.of("refreshToken",refresh(owner)),null);
            try(var replacement=channel(rotated,id)) {
                var initial=replacement.next("SNAPSHOT");assertEquals(1,initial.at("/data/room/members").size());
                assertEquals(4009,old.closed.get(8,TimeUnit.SECONDS));
                replacement.socket.sendText("{\"type\":\"PING\"}",true).join();replacement.next("PONG");
                assertEquals(204,request("POST","/auth/logout",null,access(rotated)).status);
                assertTrue(Set.of(4001,4003).contains(replacement.closed.get(8,TimeUnit.SECONDS)));
            }
        }
    }
    @Test void channelExpiryAndInvalidFramesCannotKeepMembershipAlive() throws Exception {
        Reply owner=register();String id=roomId(roomCommand("POST","/rooms",owner,Map.of("name","Caducidad","capacity",2)));
        try(var socket=channel(owner,id)) {
            socket.next("SNAPSHOT");socket.socket.sendText("{\"type\":\"MESSAGE_CREATED\",\"text\":\"Falso\"}",true).join();
            assertEquals(1008,socket.closed.get(8,TimeUnit.SECONDS));
        }
        clock.now=clock.now.plusSeconds(30);request("GET","/users/me",null,access(owner));
        assertEquals(200,request("GET","/rooms/"+id,null,access(owner)).status);
        clock.now=clock.now.plusSeconds(15);
        error(request("GET","/rooms/"+id,null,access(owner)),403,"NOT_ROOM_MEMBER");
    }
    @Test void openChannelStopsAuthorizingAsSoonAsItsBearerExpires() throws Exception {
        Reply owner=register();String id=roomId(roomCommand("POST","/rooms",owner,Map.of("name","Token corto","capacity",2)));
        var probe=new ChannelProbe();
        probe.socket=client.newWebSocketBuilder().header("Authorization","Bearer "+signed(owner,"brilliant-game",clock.instant().plusSeconds(1)))
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/v1/rooms/"+id+"/events"),probe).join();
        try(probe) {
            probe.next("SNAPSHOT");clock.now=clock.now.plusSeconds(2);
            assertEquals(4001,probe.closed.get(8,TimeUnit.SECONDS));
            assertEquals(200,request("GET","/users/me",null,access(owner)).status);
        }
    }
    @Test void simultaneousHandshakesKeepExactlyOneWorkingConnection() throws Exception {
        Reply owner=register();String id=roomId(roomCommand("POST","/rooms",owner,Map.of("name","Dos canales","capacity",2)));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->channel(owner,id));var second=pool.submit(()->channel(owner,id));
            try(var a=first.get(8,TimeUnit.SECONDS);var b=second.get(8,TimeUnit.SECONDS)) {
                assertEquals(4009,CompletableFuture.anyOf(a.closed,b.closed).get(8,TimeUnit.SECONDS));
                assertNotEquals(a.closed.isDone(),b.closed.isDone());
                var survivor=a.closed.isDone()?b:a;
                survivor.next("SNAPSHOT");survivor.socket.sendText("{\"type\":\"PING\"}",true).join();survivor.next("PONG");
                assertFalse(survivor.closed.isDone());
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM room_members WHERE room_id=? AND left_at IS NULL",Integer.class,id));
            }
        }
    }
    @Autowired com.brilliant.server.rooms.RoomService roomService;
    record GameSetup(List<Reply> players,String room,String match) {}
    GameSetup game(int count) throws Exception {
        var players=new ArrayList<Reply>();for(int i=0;i<count;i++) players.add(register());
        String room=roomId(roomCommand("POST","/rooms",players.getFirst(),Map.of("name","Rondas","capacity",6)));
        for(int i=1;i<count;i++) roomCommand("POST","/rooms/"+room+"/join",players.get(i),Map.of());
        roomCommand("PATCH","/rooms/"+room+"/settings",players.getFirst(),Map.of("expectedRevision",0,"capacity",6,"levelId","tablero_01"));
        for(int i=1;i<count;i++) roomCommand("PUT","/rooms/"+room+"/ready",players.get(i),Map.of("expectedRevision",1,"ready",true));
        var started=roomCommand("POST","/rooms/"+room+"/start",players.getFirst(),Map.of("expectedRevision",1));
        assertEquals(200,started.status);return new GameSetup(players,room,started.body.at("/data/match/id").asText());
    }
    Map<String,Integer> preparation(int offset) throws Exception {
        try(var stream=getClass().getResourceAsStream("/multiplayer/parity-v1.json")) {
            for(var state:json.readTree(stream).path("states")) if(state.path("preparation").asBoolean() && state.path("boardId").asText().equals("tablero_01")) {
                var values=new LinkedHashMap<String,Integer>();state.path("placements").fields().forEachRemaining(e->values.put(e.getKey(),(e.getValue().asInt()-1+offset)%6+1));return values;
            }
        }
        throw new IllegalStateException("Falta la preparación compartida");
    }
    Reply prepare(GameSetup game,int player) throws Exception {
        return roomCommand("PUT","/matches/"+game.match+"/preparation",game.players.get(player),Map.of("placements",preparation(player)));
    }
    Reply preparedGame(GameSetup game) throws Exception {
        Reply latest=null;for(int i=0;i<game.players.size();i++) {latest=prepare(game,i);assertEquals(200,latest.status);}return latest;
    }
    void healthyActivity() {
        db.update("UPDATE sessions SET last_seen_at=? WHERE revoked_at IS NULL",clock.now.toString());
        db.update("UPDATE room_members SET last_seen_at=? WHERE left_at IS NULL",clock.now.toString());
    }
    @Test void sixIndependentPreparationsStartOneCommonRoundAndNeverExposeOtherBoards() throws Exception {
        var game=game(6);var channels=new ArrayList<ChannelProbe>();
        try {
            for(var player:game.players) {var c=channel(player,game.room);c.next("SNAPSHOT");channels.add(c);}
            for(int actor=0;actor<6;actor++) {
                var accepted=prepare(game,actor);assertEquals(200,accepted.status);
                for(int viewer=0;viewer<6;viewer++) {
                    var frame=channels.get(viewer).next("PREPARATION_CONFIRMED");
                    assertEquals(viewer<=actor?json.valueToTree(preparation(viewer)):json.createObjectNode(),frame.at("/data/match/ownBoard"));
                    assertEquals(viewer<=actor,frame.at("/data/match/ownPreparationConfirmed").asBoolean());
                    assertTrue(frame.at("/data/match/round").isNull());
                }
                if(actor<5) {assertEquals("PREPARING",accepted.body.at("/data/match/state").asText());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));}
            }
            JsonNode common=null;
            for(int i=0;i<6;i++) {
                var frame=channels.get(i).next("ROUND_STARTED");var round=frame.at("/data/match/round");
                if(common==null) common=round;else assertEquals(common,round);
                assertEquals(json.valueToTree(preparation(i)),frame.at("/data/match/ownBoard"));
                assertEquals(60,Duration.between(Instant.parse(round.path("startedAt").asText()),Instant.parse(round.path("deadline").asText())).toSeconds());
                assertEquals(json.valueToTree(preparation(i)),request("GET","/rooms/"+game.room,null,access(game.players.get(i))).body.at("/match/ownBoard"));
            }
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM room_events e,json_each(e.data_json,'$.match.ownBoard') cell WHERE e.room_id=?",Integer.class,game.room));
        } finally {for(var c:channels) c.close();}
    }
    @Test void invalidPreparationCannotChangeBoardAndReorderedReplayIsCanonical() throws Exception {
        var game=game(2);var good=preparation(0);var bad=new LinkedHashMap<>(good);
        bad.put(bad.keySet().iterator().next(),7);
        error(roomCommand("PUT","/matches/"+game.match+"/preparation",game.players.getFirst(),Map.of("placements",bad)),400,"INVALID_PREPARATION");
        error(roomCommand("PUT","/matches/"+game.match+"/preparation",game.players.getFirst(),Map.of("placements",good,"points",999)),400,"VALIDATION_ERROR");
        var outsider=register();error(roomCommand("PUT","/matches/"+game.match+"/preparation",outsider,Map.of("placements",good)),403,"NOT_ROOM_MEMBER");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM match_players WHERE match_id=? AND prepared_at IS NOT NULL",Integer.class,game.match));
        var cid=UUID.randomUUID().toString();var accepted=request("PUT","/matches/"+game.match+"/preparation",Map.of("commandId",cid,"placements",good),access(game.players.getFirst()));
        var reversed=new LinkedHashMap<String,Integer>();var keys=new ArrayList<>(good.keySet());Collections.reverse(keys);keys.forEach(k->reversed.put(k,good.get(k)));
        assertEquals(accepted.body,request("PUT","/matches/"+game.match+"/preparation",Map.of("placements",reversed,"commandId",cid),access(game.players.getFirst())).body);
        error(prepare(game,0),409,"PREPARATION_ALREADY_CONFIRMED");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
    }
    @Test void simultaneousPreparationsCreateExactlyOneFirstRound() throws Exception {
        var game=game(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->prepare(game,0));var b=pool.submit(()->prepare(game,1));assertEquals(200,a.get().status);assertEquals(200,b.get().status);
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
    }
    @Test void acceptedPlacementIsFinalPrivateAndIdempotent() throws Exception {
        var game=game(2);var initial=preparedGame(game);String round=initial.body.at("/data/match/round/id").asText();
        var dice=initial.body.at("/data/match/round/dice");int first=dice.get(0).asInt(),second=dice.get(1).asInt();
        var rules=new com.brilliant.server.game.BoardRules(new com.brilliant.server.game.BoardCatalog().board("tablero_01"));
        int die=rules.destinations(preparation(0),first,second).isEmpty()?1:0;
        String cell=rules.destinations(preparation(0),die==0?first:second,die==0?second:first).iterator().next();
        String route="/matches/"+game.match+"/rounds/"+round+"/actions",cid=UUID.randomUUID().toString();
        var body=Map.of("commandId",cid,"kind","PLACE","dieIndex",die,"cellId",cell);
        var accepted=request("POST",route,body,access(game.players.getFirst()));assertEquals(200,accepted.status);
        assertEquals(7,accepted.body.at("/data/match/ownBoard").size());assertEquals("PLACE",accepted.body.at("/data/match/round/ownAction/kind").asText());
        assertEquals(accepted.body,request("POST",route,body,access(game.players.getFirst())).body);
        error(roomCommand("POST",route,game.players.getFirst(),Map.of("kind","SKIP")),409,"ACTION_ALREADY_RESOLVED");
        var opponent=request("GET","/rooms/"+game.room,null,access(game.players.get(1)));
        assertEquals(json.valueToTree(preparation(1)),opponent.body.at("/match/ownBoard"));assertTrue(opponent.body.at("/match/round/ownAction").isNull());
        assertEquals(200,roomCommand("POST",route,game.players.get(1),Map.of("kind","SKIP")).status);
        error(roomCommand("POST",route,game.players.getFirst(),Map.of("kind","SKIP")),409,"ROUND_EXPIRED");
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM round_actions WHERE round_id=? AND member_id=(SELECT id FROM room_members WHERE user_id=? AND room_id=?)",Integer.class,round,game.players.getFirst().body.at("/user/id").asText(),game.room));
    }
    @Test void invalidAndExpiredActionsDoNotCommitLocalProposals() throws Exception {
        var game=game(2);var initial=preparedGame(game);String round=initial.body.at("/data/match/round/id").asText();String route="/matches/"+game.match+"/rounds/"+round+"/actions";
        String occupied=preparation(0).keySet().iterator().next();
        error(roomCommand("POST",route,game.players.getFirst(),Map.of("kind","PLACE","dieIndex",0,"cellId",occupied)),400,"INVALID_MOVE");
        error(roomCommand("POST",route,game.players.getFirst(),Map.of("kind","PLACE","dieIndex",2,"cellId",occupied)),400,"INVALID_MOVE");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM round_actions WHERE round_id=?",Integer.class,round));
        clock.now=clock.now.plusSeconds(59);healthyActivity();roomService.advanceMatches();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
        clock.now=clock.now.plusSeconds(1);healthyActivity();roomService.advanceMatches();roomService.advanceMatches();
        error(roomCommand("POST",route,game.players.getFirst(),Map.of("kind","PLACE","dieIndex",0,"cellId",occupied)),409,"ROUND_EXPIRED");
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM round_actions WHERE round_id=? AND kind='SKIP' AND reason='TIMEOUT'",Integer.class,round));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
        for(int i=0;i<2;i++) assertEquals(json.valueToTree(preparation(i)),request("GET","/rooms/"+game.room,null,access(game.players.get(i))).body.at("/match/ownBoard"));
    }
    @Test void lastConfirmationRacingTimeoutAdvancesOnlyOnce() throws Exception {
        var game=game(2);var initial=preparedGame(game);String round=initial.body.at("/data/match/round/id").asText();String route="/matches/"+game.match+"/rounds/"+round+"/actions";
        roomCommand("POST",route,game.players.getFirst(),Map.of("kind","SKIP"));
        clock.now=clock.now.plusSeconds(59);healthyActivity();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var action=pool.submit(()->roomCommand("POST",route,game.players.get(1),Map.of("kind","SKIP")));
            var timeout=pool.submit(()->{clock.now=clock.now.plusSeconds(1);roomService.advanceMatches();});
            int status=action.get().status;timeout.get();assertTrue(Set.of(200,409).contains(status));
        }
        roomService.advanceMatches();assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM round_actions WHERE round_id=?",Integer.class,round));
    }
    @Test void leavingPreparationStopsWaitingForTheDepartedParticipant() throws Exception {
        var game=game(3);prepare(game,0);prepare(game,1);
        var left=roomCommand("POST","/rooms/"+game.room+"/leave",game.players.get(2),Map.of());assertEquals(200,left.status);
        assertEquals("ROUND_ACTIVE",left.body.at("/data/match/state").asText());assertEquals(24,left.body.at("/data/match/target").asInt());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
    }
    String memberId(GameSetup game,int i) {
        return db.queryForObject("SELECT id FROM room_members WHERE room_id=? AND user_id=?",String.class,game.room,game.players.get(i).body.at("/user/id").asText());
    }
    void greenAlmostComplete(GameSetup game) throws Exception {
        var board=new LinkedHashMap<>(preparation(0));
        for(String cell:List.of("f2_c1","f2_c2","f3_c1","f4_c1")) board.put(cell,1);
        db.update("UPDATE match_players SET board_json=? WHERE match_id=?",json.writeValueAsString(board),game.match);
        db.update("UPDATE match_rounds SET first_die=1,second_die=1 WHERE match_id=? AND closed_at IS NULL",game.match);
    }
    String actionRoute(GameSetup game) {
        return "/matches/"+game.match+"/rounds/"+db.queryForObject("SELECT id FROM match_rounds WHERE match_id=? AND closed_at IS NULL",String.class,game.match)+"/actions";
    }
    void blockedBoards(GameSetup game) throws Exception {
        var board=new LinkedHashMap<String,Integer>();
        for(var zone:new com.brilliant.server.game.BoardCatalog().board("tablero_01").zones()) {
            int i=0;for(var cell:zone.cells()) board.put(cell.id(),switch(zone.rule()) {case RED,YELLOW->++i;default->1;});
        }
        new com.brilliant.server.game.BoardRules(new com.brilliant.server.game.BoardCatalog().board("tablero_01")).validate(board);
        db.update("UPDATE match_players SET board_json=? WHERE match_id=?",json.writeValueAsString(board),game.match);
    }
    Reply skipAll(GameSetup game) throws Exception {
        String route=actionRoute(game);Reply last=null;
        for(var player:game.players) {last=roomCommand("POST",route,player,Map.of("kind","SKIP"));assertEquals(200,last.status);}
        return last;
    }
    JsonNode openBlockedVote(GameSetup game) throws Exception {
        preparedGame(game);blockedBoards(game);
        for(int i=0;i<3;i++) skipAll(game);
        var snapshot=request("GET","/rooms/"+game.room,null,access(game.players.getFirst())).body;
        assertEquals("END_VOTE",snapshot.at("/match/state").asText());return snapshot.at("/match/endVote");
    }
    Reply ballot(GameSetup game,int player,String vote,boolean approve) throws Exception {
        return roomCommand("PUT","/matches/"+game.match+"/end-vote/ballot",game.players.get(player),Map.of("voteId",vote,"approve",approve));
    }
    @Test void sixConcurrentClaimsReceiveUniquePrizesAndReplaysCannotScoreTwice() throws Exception {
        var game=game(6);preparedGame(game);greenAlmostComplete(game);String route=actionRoute(game);
        var bodies=new ArrayList<Map<String,Object>>();var replies=new ArrayList<Reply>();
        for(int i=0;i<6;i++) bodies.add(Map.of("commandId",UUID.randomUUID().toString(),"kind","PLACE","dieIndex",0,"cellId","f5_c1"));
        try(var pool=Executors.newFixedThreadPool(6)) {
            var tasks=new ArrayList<Future<Reply>>();
            for(int i=0;i<6;i++) {int actor=i;tasks.add(pool.submit(()->request("POST",route,bodies.get(actor),access(game.players.get(actor)))));}
            for(var task:tasks) {var accepted=task.get();assertEquals(200,accepted.status);replies.add(accepted);}
        }
        assertEquals(List.of(4,3,2,0,0,0),db.queryForList("SELECT points FROM zone_claims WHERE match_id=? ORDER BY place",Integer.class,game.match));
        assertEquals(6,db.queryForObject("SELECT COUNT(DISTINCT member_id) FROM zone_claims WHERE match_id=?",Integer.class,game.match));
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM room_messages WHERE room_id=? AND text LIKE '%completó verde_1%'",Integer.class,game.room));
        for(int i=0;i<6;i++) assertEquals(replies.get(i).body,request("POST",route,bodies.get(i),access(game.players.get(i))).body);
        var snapshot=request("GET","/rooms/"+game.room,null,access(game.players.getFirst())).body;
        assertEquals(9,snapshot.at("/match/scores").findValues("points").stream().mapToInt(JsonNode::asInt).sum());
        assertEquals(7,snapshot.at("/match/zones/1/nextPlace").asInt());assertEquals(0,snapshot.at("/match/zones/1/nextPoints").asInt());
        assertEquals(2,snapshot.at("/match/round/number").asInt());
        assertEquals(200,roomCommand("POST","/rooms/"+game.room+"/leave",game.players.getFirst(),Map.of()).status);
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM zone_claims WHERE match_id=?",Integer.class,game.match));
        assertEquals(20,request("GET","/rooms/"+game.room,null,access(game.players.get(1))).body.at("/match/target").asInt());
    }
    @Test void reachingTargetFreezesOneWinnerAndRejectsLaterActions() throws Exception {
        var game=game(6);preparedGame(game);greenAlmostComplete(game);String actor=memberId(game,0),route=actionRoute(game);
        // A trusted persisted score immediately before a winning green-zone placement.
        db.update("INSERT INTO zone_claims VALUES(?, 'azul_1',?,1,7,1)",game.match,actor);
        db.update("INSERT INTO zone_claims VALUES(?, 'morado_1',?,1,6,2)",game.match,actor);
        db.update("INSERT INTO zone_claims VALUES(?, 'rojo_1',?,1,6,3)",game.match,actor);
        var accepted=roomCommand("POST",route,game.players.getFirst(),Map.of("kind","PLACE","dieIndex",0,"cellId","f5_c1"));assertEquals(200,accepted.status);
        var result=accepted.body.at("/data/match/result");assertEquals(actor,result.path("winnerMemberId").asText());assertEquals("TARGET",result.path("reason").asText());
        assertEquals(23,result.at("/ranking/0/points").asInt());assertEquals("FINISHED",accepted.body.at("/data/match/state").asText());
        error(roomCommand("POST",route,game.players.get(1),Map.of("kind","PLACE","dieIndex",0,"cellId","f5_c1")),409,"MATCH_FINISHED");
        roomCommand("POST","/rooms/"+game.room+"/leave",game.players.getFirst(),Map.of());
        assertEquals(result,request("GET","/rooms/"+game.room,null,access(game.players.get(1))).body.at("/match/result"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM room_events WHERE room_id=? AND type='MATCH_FINISHED'",Integer.class,game.room));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
    }
    @Test void votingRequiresAbsoluteMajorityForEverySupportedPlayerCount() throws Exception {
        for(int n=2;n<=6;n++) {
            var game=game(n);var vote=openBlockedVote(game);int required=n/2+1;
            assertEquals(required,vote.path("required").asInt());
            assertEquals(60,Duration.between(Instant.parse(vote.path("startedAt").asText()),Instant.parse(vote.path("deadline").asText())).toSeconds());
            assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
            error(roomCommand("POST","/matches/"+game.match+"/rounds/old/actions",game.players.getFirst(),Map.of("kind","SKIP")),409,"VOTE_ACTIVE");
            for(int i=0;i<required;i++) {
                var reply=ballot(game,i,vote.path("id").asText(),true);assertEquals(200,reply.status);
                assertEquals(i==required-1?"FINISHED":"END_VOTE",reply.body.at("/data/match/state").asText());
                if(i==required-1) {assertEquals(memberId(game,0),reply.body.at("/data/match/result/winnerMemberId").asText());assertEquals("END_VOTE",reply.body.at("/data/match/result/reason").asText());}
            }
        }
    }
    @Test void voteTimeoutResumesAndRequiresThreeMoreBlockedRounds() throws Exception {
        var game=game(3);var vote=openBlockedVote(game);ballot(game,0,vote.path("id").asText(),true);
        clock.now=clock.now.plusSeconds(59);healthyActivity();roomService.advanceMatches();
        assertEquals("END_VOTE",db.queryForObject("SELECT state FROM room_matches WHERE id=?",String.class,game.match));
        clock.now=clock.now.plusSeconds(1);healthyActivity();roomService.advanceMatches();roomService.advanceMatches();
        assertEquals("ROUND_ACTIVE",db.queryForObject("SELECT state FROM room_matches WHERE id=?",String.class,game.match));
        assertEquals("EXPIRED",db.queryForObject("SELECT outcome FROM end_votes WHERE id=?",String.class,vote.path("id").asText()));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
        error(ballot(game,1,vote.path("id").asText(),true),409,"VOTE_NOT_ACTIVE");
        for(int i=0;i<2;i++) assertEquals("ROUND_ACTIVE",skipAll(game).body.at("/data/match/state").asText());
        assertEquals("END_VOTE",skipAll(game).body.at("/data/match/state").asText());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM end_votes WHERE match_id=?",Integer.class,game.match));
    }
    @Test void availableMovesBreakBlockedStreakEvenWhenEveryoneSkipsOrTimesOut() throws Exception {
        var game=game(2);preparedGame(game);db.update("UPDATE match_progress SET blocked_rounds=2 WHERE match_id=?",game.match);
        db.update("UPDATE match_rounds SET first_die=1,second_die=1 WHERE match_id=?",game.match);
        skipAll(game);assertEquals(0,db.queryForObject("SELECT blocked_rounds FROM match_progress WHERE match_id=?",Integer.class,game.match));
        db.update("UPDATE match_progress SET blocked_rounds=2 WHERE match_id=?",game.match);
        db.update("UPDATE match_rounds SET first_die=1,second_die=1 WHERE match_id=? AND closed_at IS NULL",game.match);
        clock.now=clock.now.plusSeconds(60);healthyActivity();roomService.advanceMatches();
        assertEquals(0,db.queryForObject("SELECT blocked_rounds FROM match_progress WHERE match_id=?",Integer.class,game.match));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM end_votes WHERE match_id=?",Integer.class,game.match));
    }
    @Test void ballotIsImmutableIdempotentAndUnauthorizedVotesHaveNoEffect() throws Exception {
        var game=game(3);var vote=openBlockedVote(game);String route="/matches/"+game.match+"/end-vote/ballot";
        var body=Map.of("commandId",UUID.randomUUID().toString(),"voteId",vote.path("id").asText(),"approve",true);
        var first=request("PUT",route,body,access(game.players.getFirst()));assertEquals(200,first.status);
        assertEquals(first.body,request("PUT",route,body,access(game.players.getFirst())).body);
        error(ballot(game,0,vote.path("id").asText(),false),409,"BALLOT_ALREADY_CAST");
        var outsider=register();error(request("PUT",route,body,access(outsider)),403,"NOT_ROOM_MEMBER");
        error(roomCommand("PUT",route,game.players.get(1),Map.of("voteId",vote.path("id").asText(),"approve",true,"points",999)),400,"VALIDATION_ERROR");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM end_ballots WHERE vote_id=?",Integer.class,vote.path("id").asText()));
        assertEquals(vote.path("id"),request("GET","/matches/"+game.match+"/end-vote",null,access(game.players.getFirst())).body.at("/endVote/id"));
    }
    @Test void departuresWithdrawVotesRecalculateMajorityAndPreserveFrozenTarget() throws Exception {
        var game=game(4);var vote=openBlockedVote(game);String id=vote.path("id").asText();ballot(game,0,id,true);ballot(game,1,id,true);
        var left=roomCommand("POST","/rooms/"+game.room+"/leave",game.players.get(0),Map.of());assertEquals(200,left.status);
        assertEquals("END_VOTE",left.body.at("/data/match/state").asText());assertEquals(2,left.body.at("/data/match/endVote/required").asInt());
        assertEquals(1,left.body.at("/data/match/endVote/ballots").size());
        assertEquals(memberId(game,1),left.body.at("/data/room/hostMemberId").asText());
        var approved=ballot(game,2,id,true);assertEquals("FINISHED",approved.body.at("/data/match/state").asText());
        assertEquals(22,approved.body.at("/data/match/target").asInt());
        assertEquals(memberId(game,1),approved.body.at("/data/match/result/winnerMemberId").asText());
        assertEquals(memberId(game,0),approved.body.at("/data/match/result/ranking/3/memberId").asText());
    }
    @Test void impossibleMajorityRejectsVoteAndContinuesWithoutWaitingForTimeout() throws Exception {
        var game=game(2);var vote=openBlockedVote(game);
        var rejected=ballot(game,0,vote.path("id").asText(),false);assertEquals("ROUND_ACTIVE",rejected.body.at("/data/match/state").asText());
        assertEquals("REJECTED",rejected.body.at("/data/match/endVote/outcome").asText());
        assertEquals(0,rejected.body.at("/data/match/blockedRounds").asInt());
    }
    @Test void lastRemainingPlayerWinsInPreparationOrVoteRegardlessOfWhoLeaves() throws Exception {
        for(boolean hostLeaves:List.of(true,false)) {
            var game=game(2);int leaver=hostLeaves?0:1;
            var left=roomCommand("POST","/rooms/"+game.room+"/leave",game.players.get(leaver),Map.of());
            assertEquals("FINISHED",left.body.at("/data/match/state").asText());
            assertEquals(memberId(game,1-leaver),left.body.at("/data/match/result/winnerMemberId").asText());
            assertEquals("ABANDONMENT",left.body.at("/data/match/result/reason").asText());
            assertEquals(26,left.body.at("/data/match/target").asInt());
        }
        var voting=game(2);openBlockedVote(voting);
        var winner=roomCommand("POST","/rooms/"+voting.room+"/leave",voting.players.getFirst(),Map.of());
        assertEquals("ABANDONMENT",winner.body.at("/data/match/result/reason").asText());
    }
    @Test void simultaneousPresenceExpiryDoesNotAwardLastInDatabaseAnAccidentalVictory() throws Exception {
        var game=game(3);preparedGame(game);clock.now=clock.now.plusSeconds(45);roomService.expireMembers();
        var result=json.readTree(db.queryForObject("SELECT result_json FROM match_progress WHERE match_id=?",String.class,game.match));
        assertTrue(result.path("winnerMemberId").isNull());assertEquals("NO_PARTICIPANTS",result.path("reason").asText());
        assertEquals("CLOSED",db.queryForObject("SELECT state FROM rooms WHERE id=?",String.class,game.room));
    }
    @Autowired com.brilliant.server.game.MatchScoring matchScoring;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Test void failedAwardEventRollsBackTheEntirePlacementAndClaim() throws Exception {
        var game=game(2);preparedGame(game);greenAlmostComplete(game);
        int before=db.queryForObject("SELECT length(board_json) FROM match_players WHERE match_id=? AND member_id=?",Integer.class,game.match,memberId(game,0));
        assertThrows(IllegalStateException.class,()->com.brilliant.server.database.Database.write(transactions,()->{
            var engine=new com.brilliant.server.game.MatchEngine(db,json,clock,matchScoring);
            engine.action(game.match,db.queryForObject("SELECT id FROM match_rounds WHERE match_id=?",String.class,game.match),memberId(game,0),"PLACE",0,"f5_c1",(type,text)->{if(type.equals("ZONE_CLAIMED"))throw new IllegalStateException("Evento no persistido");});return null;
        }));
        assertEquals(before,db.queryForObject("SELECT length(board_json) FROM match_players WHERE match_id=? AND member_id=?",Integer.class,game.match,memberId(game,0)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM zone_claims WHERE match_id=?",Integer.class,game.match));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM round_actions WHERE round_id IN (SELECT id FROM match_rounds WHERE match_id=?)",Integer.class,game.match));
    }
    @Test void finalOrderUsesZonesThenLastPositiveAwardThenJoinOrderAndDeparturesLast() throws Exception {
        var game=game(6);preparedGame(game);
        String a=memberId(game,0),b=memberId(game,1),c=memberId(game,2),d=memberId(game,3),e=memberId(game,4),f=memberId(game,5);
        // Same points: B has an extra zero-point completion and precedes A.
        db.update("INSERT INTO zone_claims VALUES(?, 'verde_1',?,1,4,10)",game.match,a);
        db.update("INSERT INTO zone_claims VALUES(?, 'verde_2',?,1,4,20)",game.match,b);
        db.update("INSERT INTO zone_claims VALUES(?, 'morado_1',?,4,0,30)",game.match,b);
        // Same points and zone count: positive-award sequence precedes the zero.
        db.update("INSERT INTO zone_claims VALUES(?, 'rojo_1',?,2,4,5)",game.match,c);
        db.update("INSERT INTO zone_claims VALUES(?, 'rojo_2',?,4,0,40)",game.match,c);
        db.update("UPDATE room_members SET left_at=? WHERE id=?",clock.now.toString(),c);
        com.brilliant.server.database.Database.write(transactions,()->{
            matchScoring.finish(new com.brilliant.server.game.MatchEngine(db,json,clock,matchScoring).match(game.match),f,"ABANDONMENT",(type,text)->{});return null;
        });
        var result=json.valueToTree(matchScoring.result(game.match));
        assertEquals(List.of(f,b,a,d,e,c),result.path("ranking").findValuesAsText("memberId"));
        assertEquals(20,result.at("/ranking/1/scoreSequence").asInt());
    }
    @Test void expiredBallotsAndSimultaneousApprovalsFinishOnlyOnce() throws Exception {
        var game=game(3);var vote=openBlockedVote(game);String id=vote.path("id").asText();ballot(game,0,id,true);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->ballot(game,1,id,true));var b=pool.submit(()->ballot(game,2,id,true));
            var statuses=List.of(a.get().status,b.get().status);assertTrue(statuses.contains(200));assertTrue(statuses.stream().allMatch(x->x==200 || x==409));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM room_events WHERE room_id=? AND type='MATCH_FINISHED'",Integer.class,game.room));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,game.match));
    }
    @Test void voteAndFrozenResultSurviveRealChannelReplacement() throws Exception {
        var game=game(3);var vote=openBlockedVote(game);String voteId=vote.path("id").asText();
        try(var first=channel(game.players.get(2),game.room)) {
            assertEquals(vote,first.next("SNAPSHOT").at("/data/match/endVote"));
            ballot(game,0,voteId,true);
            assertEquals(1,first.next("END_VOTE_UPDATED").at("/data/match/endVote/ballots").size());
        }
        try(var replacement=channel(game.players.get(2),game.room)) {
            var snapshot=replacement.next("SNAPSHOT");assertEquals(voteId,snapshot.at("/data/match/endVote/id").asText());
            assertEquals(1,snapshot.at("/data/match/endVote/ballots").size());
            var finished=ballot(game,1,voteId,true);assertEquals(200,finished.status);
            assertEquals(finished.body.at("/data/match/result"),replacement.next("MATCH_FINISHED").at("/data/match/result"));
        }
        try(var result=channel(game.players.get(2),game.room)) {
            assertEquals("END_VOTE",result.next("SNAPSHOT").at("/data/match/result/reason").asText());
        }
    }
    @Test void deletingAnAccountPreservesClaimsNamesAndTheNextAvailablePrize() throws Exception {
        var game=game(3);preparedGame(game);greenAlmostComplete(game);String route=actionRoute(game);
        String departed=memberId(game,0),username=game.players.getFirst().body.at("/user/username").asText();
        assertEquals(200,roomCommand("POST",route,game.players.getFirst(),Map.of("kind","PLACE","dieIndex",0,"cellId","f5_c1")).status);
        assertEquals(204,request("DELETE","/users/me",Map.of("currentPassword",PASSWORD),access(game.players.getFirst())).status);
        var remaining=request("GET","/rooms/"+game.room,null,access(game.players.get(1))).body;
        assertEquals(24,remaining.at("/match/target").asInt());assertEquals("ROUND_ACTIVE",remaining.at("/match/state").asText());
        assertEquals(memberId(game,1),remaining.at("/room/hostMemberId").asText());
        var historical=remaining.at("/match/scores/0");assertEquals(departed,historical.path("memberId").asText());assertEquals(username,historical.path("username").asText());
        assertEquals(4,historical.path("points").asInt());assertEquals(1,historical.path("abandoned").asInt());
        var second=roomCommand("POST",route,game.players.get(1),Map.of("kind","PLACE","dieIndex",0,"cellId","f5_c1"));assertEquals(200,second.status);
        assertEquals(List.of(4,3),db.queryForList("SELECT points FROM zone_claims WHERE match_id=? ORDER BY place",Integer.class,game.match));
        assertNull(db.queryForObject("SELECT user_id FROM room_members WHERE id=?",String.class,departed));
    }
    GameSetup finishedGame(int players) throws Exception {
        var game=game(players);var vote=openBlockedVote(game);
        for(int i=0;i<players/2+1;i++) assertEquals(200,ballot(game,i,vote.path("id").asText(),true).status);
        return game;
    }
    Reply acceptRematch(GameSetup game,int player) throws Exception {
        return roomCommand("POST","/matches/"+game.match+"/rematch",game.players.get(player),Map.of());
    }
    @Test void partialRematchKeepsOldResultsAndStartsCleanWithNewTarget() throws Exception {
        var game=finishedGame(6);
        var old=request("GET","/rooms/"+game.room,null,access(game.players.getFirst())).body.at("/match/result");
        var first=acceptRematch(game,1);assertEquals(200,first.status);assertTrue(first.body.at("/data/match").isNull());
        assertEquals(memberId(game,1),first.body.at("/data/room/hostMemberId").asText());assertEquals(1,first.body.at("/data/room/members").size());
        assertTrue(first.body.at("/data/room/levelId").isNull());
        assertEquals(old,request("GET","/rooms/"+game.room,null,access(game.players.getFirst())).body.at("/match/result"));
        error(roomCommand("PUT","/rooms/"+game.room+"/ready",game.players.getFirst(),Map.of("expectedRevision",2,"ready",true)),409,"REMATCH_NOT_ACCEPTED");
        var second=acceptRematch(game,0);assertEquals(memberId(game,1),second.body.at("/data/room/hostMemberId").asText());
        for(var m:second.body.at("/data/room/members")) assertFalse(m.path("ready").asBoolean());
        var selected=roomCommand("PATCH","/rooms/"+game.room+"/settings",game.players.get(1),Map.of("expectedRevision",2,"capacity",6,"levelId","tablero_01"));assertEquals(200,selected.status);
        error(roomCommand("POST","/rooms/"+game.room+"/start",game.players.get(1),Map.of("expectedRevision",3)),409,"PLAYERS_NOT_READY");
        roomCommand("PUT","/rooms/"+game.room+"/ready",game.players.getFirst(),Map.of("expectedRevision",3,"ready",true));
        var started=roomCommand("POST","/rooms/"+game.room+"/start",game.players.get(1),Map.of("expectedRevision",3));assertEquals(200,started.status);
        var fresh=started.body.at("/data/match");String newId=fresh.path("id").asText();assertNotEquals(game.match,newId);
        assertEquals(2,started.body.at("/data/room/members").size());
        assertEquals(26,fresh.path("target").asInt());assertEquals(2,fresh.path("participants").size());assertEquals(0,fresh.path("ownBoard").size());
        assertEquals(0,fresh.path("claims").size());assertEquals(0,fresh.path("preparedMemberIds").size());assertTrue(fresh.path("round").isNull());assertTrue(fresh.path("result").isNull());
        assertEquals(0,fresh.path("scores").findValues("points").stream().mapToInt(JsonNode::asInt).sum());
        var stayed=request("GET","/rooms/"+game.room,null,access(game.players.get(2))).body;
        assertEquals(game.match,stayed.at("/match/id").asText());assertEquals(old,stayed.at("/match/result"));assertEquals("FINISHED",stayed.at("/room/state").asText());assertFalse(stayed.at("/match/rematch/available").asBoolean());
        error(acceptRematch(game,2),409,"REMATCH_CLOSED");
        error(roomCommand("PUT","/matches/"+newId+"/preparation",game.players.get(2),Map.of("placements",preparation(0))),403,"NOT_MATCH_PARTICIPANT");
        for(int i:List.of(0,1)) assertEquals(200,roomCommand("PUT","/matches/"+newId+"/preparation",game.players.get(i),Map.of("placements",preparation(i))).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM match_rounds WHERE match_id=?",Integer.class,newId));
        assertEquals(old,request("GET","/rooms/"+game.room,null,access(game.players.get(2))).body.at("/match/result"));
    }
    @Test void newPlayersCanJoinRematchWithoutReservingPlacesForOldResultViewers() throws Exception {
        var game=finishedGame(6);acceptRematch(game,1);
        for(int i=0;i<5;i++) {var newcomer=register();assertEquals(200,roomCommand("POST","/rooms/"+game.room+"/join",newcomer,Map.of()).status);}
        var outsider=register();error(roomCommand("POST","/rooms/"+game.room+"/join",outsider,Map.of()),409,"ROOM_FULL");
        error(acceptRematch(game,0),409,"ROOM_FULL");
        assertEquals(game.match,request("GET","/rooms/"+game.room,null,access(game.players.getFirst())).body.at("/match/id").asText());
        var listed=request("GET","/rooms",null,access(outsider));
        var listedRoom=java.util.stream.StreamSupport.stream(listed.body.path("rooms").spliterator(),false).filter(r->r.path("id").asText().equals(game.room)).findFirst().orElseThrow();
        assertEquals(6,listedRoom.path("occupancy").asInt());assertEquals(11,db.queryForObject("SELECT COUNT(*) FROM room_members WHERE room_id=? AND left_at IS NULL",Integer.class,game.room));
    }
    @Test void rematchReceiptsAreIdempotentAndOnlyFinishedParticipantsCanAccept() throws Exception {
        var unfinished=game(2);error(acceptRematch(unfinished,0),409,"REMATCH_CLOSED");
        var game=finishedGame(3);var outsider=register();
        error(roomCommand("POST","/matches/"+game.match+"/rematch",outsider,Map.of()),403,"NOT_ROOM_MEMBER");
        var body=Map.of("commandId",UUID.randomUUID().toString());String route="/matches/"+game.match+"/rematch";
        var accepted=request("POST",route,body,access(game.players.get(1)));assertEquals(200,accepted.status);
        assertEquals(accepted.body,request("POST",route,body,access(game.players.get(1))).body);
        assertEquals(200,acceptRematch(game,1).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rematch_acceptances WHERE match_id=?",Integer.class,game.match));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM room_events WHERE room_id=? AND type='REMATCH_ACCEPTED'",Integer.class,game.room));
        error(roomCommand("POST",route,game.players.getFirst(),Map.of("points",999)),400,"VALIDATION_ERROR");
    }
    @Test void concurrentRematchAcceptancesChooseOneHostAndOldHostCannotTakeItBack() throws Exception {
        var game=finishedGame(4);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->acceptRematch(game,1));var b=pool.submit(()->acceptRematch(game,2));
            assertEquals(200,a.get().status);assertEquals(200,b.get().status);
        }
        String host=db.queryForObject("SELECT host_member_id FROM rooms WHERE id=?",String.class,game.room);
        assertTrue(Set.of(memberId(game,1),memberId(game,2)).contains(host));
        assertEquals(200,acceptRematch(game,0).status);
        assertEquals(host,db.queryForObject("SELECT host_member_id FROM rooms WHERE id=?",String.class,game.room));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM room_members WHERE room_id=? AND left_at IS NULL AND view_match_id IS NULL",Integer.class,game.room));
    }
    @Test void rematchHostDepartureTransfersOnlyToAcceptedPlayersAndEmptyLobbyCanReopen() throws Exception {
        var game=finishedGame(4);acceptRematch(game,1);acceptRematch(game,2);
        roomCommand("POST","/rooms/"+game.room+"/leave",game.players.get(1),Map.of());
        assertEquals(memberId(game,2),db.queryForObject("SELECT host_member_id FROM rooms WHERE id=?",String.class,game.room));
        roomCommand("POST","/rooms/"+game.room+"/leave",game.players.get(2),Map.of());
        assertEquals("FINISHED",db.queryForObject("SELECT state FROM rooms WHERE id=?",String.class,game.room));
        var reopened=acceptRematch(game,3);assertEquals(200,reopened.status);
        assertEquals(memberId(game,3),reopened.body.at("/data/room/hostMemberId").asText());
        assertEquals(1,reopened.body.at("/data/room/members").size());
    }
    @Test void rematchEventsAndReconnectKeepPersonalViewsAtTheirOriginalSequences() throws Exception {
        var game=finishedGame(3);var original=request("GET","/rooms/"+game.room,null,access(game.players.getFirst())).body.at("/match/result");
        try(var old=channel(game.players.getFirst(),game.room);var accepted=channel(game.players.get(1),game.room)) {
            old.next("SNAPSHOT");accepted.next("SNAPSHOT");
            acceptRematch(game,1);
            // Another acceptance commits before draining the preceding event.
            acceptRematch(game,2);
            var first=old.next("REMATCH_ACCEPTED");assertEquals(original,first.at("/data/match/result"));assertEquals(1,first.at("/data/match/rematch/acceptedCount").asInt());
            assertTrue(accepted.next("REMATCH_ACCEPTED").at("/data/match").isNull());
            assertEquals(2,old.next("REMATCH_ACCEPTED").at("/data/match/rematch/acceptedCount").asInt());
        }
        try(var old=channel(game.players.getFirst(),game.room);var lobby=channel(game.players.get(1),game.room)) {
            assertEquals(original,old.next("SNAPSHOT").at("/data/match/result"));
            var snapshot=lobby.next("SNAPSHOT");assertTrue(snapshot.at("/data/match").isNull());assertEquals(2,snapshot.at("/data/room/members").size());
        }
    }
    @Test void departureDuringTheNewGameCannotTransferHostToAnOldResultViewer() throws Exception {
        var game=finishedGame(4);acceptRematch(game,1);acceptRematch(game,2);
        roomCommand("PATCH","/rooms/"+game.room+"/settings",game.players.get(1),Map.of("expectedRevision",2,"capacity",6,"levelId","tablero_01"));
        roomCommand("PUT","/rooms/"+game.room+"/ready",game.players.get(2),Map.of("expectedRevision",3,"ready",true));
        var started=roomCommand("POST","/rooms/"+game.room+"/start",game.players.get(1),Map.of("expectedRevision",3));assertEquals(200,started.status);
        var departed=roomCommand("POST","/rooms/"+game.room+"/leave",game.players.get(1),Map.of());assertEquals(200,departed.status);
        var survivor=request("GET","/rooms/"+game.room,null,access(game.players.get(2))).body;
        assertEquals(memberId(game,2),survivor.at("/room/hostMemberId").asText());
        assertEquals(memberId(game,2),survivor.at("/match/result/winnerMemberId").asText());assertEquals("ABANDONMENT",survivor.at("/match/result/reason").asText());
        assertEquals(game.match,request("GET","/rooms/"+game.room,null,access(game.players.getFirst())).body.at("/match/id").asText());
    }
    @Test void avatarIsValidatedOwnedAndRestoredOnRefreshAndLogin() throws Exception {
        var owner=register();var other=register();String route="/users/me/avatar";
        assertTrue(owner.body.at("/user/avatarId").isNull());
        for(String avatar:List.of("tablero_01","tablero_02","tablero_03","tablero_04")) {
            var saved=request("PUT",route,Map.of("avatarId",avatar),access(owner));assertEquals(200,saved.status);assertEquals(avatar,saved.body.path("avatarId").asText());
        }
        error(request("PUT",route,Map.of("avatarId","desconocido"),access(owner)),400,"VALIDATION_ERROR");
        assertTrue(request("GET","/users/me",null,access(other)).body.path("avatarId").isNull());
        var renewed=request("POST","/auth/refresh",Map.of("refreshToken",refresh(owner)),null);assertEquals(200,renewed.status);
        assertEquals("tablero_04",renewed.body.at("/user/avatarId").asText());
        assertEquals(204,request("POST","/auth/logout",null,access(renewed)).status);
        var logged=request("POST","/auth/login",Map.of("username",owner.body.at("/user/username").asText(),"password",PASSWORD),null);
        assertEquals("tablero_04",logged.body.at("/user/avatarId").asText());
        var reset=new LinkedHashMap<String,Object>();reset.put("avatarId",null);
        assertTrue(request("PUT",route,reset,access(logged)).body.path("avatarId").isNull());
    }
    @Test void ownDiceHistorySurvivesReconnectAndNeverUsesTheOpponentsActions() throws Exception {
        var game=game(2);var initial=preparedGame(game);String route=actionRoute(game);
        roomCommand("POST",route,game.players.getFirst(),Map.of("kind","SKIP"));
        var first=request("GET","/rooms/"+game.room,null,access(game.players.getFirst())).body;
        var other=request("GET","/rooms/"+game.room,null,access(game.players.get(1))).body;
        assertEquals(1,first.at("/match/ownHistory").size());assertEquals("SKIP",first.at("/match/ownHistory/0/kind").asText());
        assertEquals(initial.body.at("/data/match/round/dice"),first.at("/match/ownHistory/0/dice"));assertEquals(0,other.at("/match/ownHistory").size());
        roomCommand("POST",route,game.players.get(1),Map.of("kind","SKIP"));
        try(var channel=channel(game.players.getFirst(),game.room)) {assertEquals(first.at("/match/ownHistory"),channel.next("SNAPSHOT").at("/data/match/ownHistory"));}
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM room_events e,json_each(e.data_json,'$.match.ownHistory') h WHERE e.room_id=?",Integer.class,game.room));
    }
    @BeforeEach void clear() throws Exception {
        clock.now=Instant.parse("2026-11-02T00:00:00Z");
        db.update("DELETE FROM users");db.update("DELETE FROM deletion_ledger");
        Files.writeString(DATA.resolve("cuentas-prueba.json"),"{}");
    }
    Reply request(String method,String route,Object body,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+route)).timeout(Duration.ofSeconds(30));
        if(token!=null) b.header("Authorization","Bearer "+token);
        b.header("Content-Type","application/json");
        String text=body==null?"":body instanceof String s?s:json.writeValueAsString(body);
        var res=client.send(b.method(method,HttpRequest.BodyPublishers.ofString(text)).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(),res.body().isEmpty()?json.nullNode():json.readTree(res.body()),res.headers());
    }
    String name() { return "P"+UUID.randomUUID().toString().replace("-","").substring(0,12); }
    Reply register(String name) throws Exception { return request("POST","/auth/register",Map.of("username",name,"password",PASSWORD),null); }
    Reply register() throws Exception { Reply r=register(name());assertEquals(201,r.status,r.body.toString());return r; }
    String access(Reply r) { return r.body.path("accessToken").asText(); }
    String refresh(Reply r) { return r.body.path("refreshToken").asText(); }
    @Test void localTestFileRecordsOnlySuccessfulAccountsAndRemovesDeletedAccount() throws Exception {
        String username=name(),password=" a!2\"\\🚀 ";
        Reply first=request("POST","/auth/register",Map.of("username",username,"password",password),null);
        assertEquals(201,first.status);
        String id=first.body.path("user").path("id").asText();
        var file=DATA.resolve("cuentas-prueba.json");
        var entries=json.readTree(file.toFile());
        assertEquals(username,entries.path(id).path("usuario").asText());
        assertEquals(password,entries.path(id).path("contrasena").asText());
        assertEquals(204,request("POST","/auth/logout",null,access(first)).status);
        Files.writeString(file,"{}");
        error(request("POST","/auth/login",Map.of("username",username,"password","wrong!"),null),401,"INVALID_CREDENTIALS");
        assertEquals(0,json.readTree(file.toFile()).size());
        Reply logged=request("POST","/auth/login",Map.of("username",username,"password",password),null);
        assertEquals(200,logged.status);
        assertEquals(password,json.readTree(file.toFile()).path(id).path("contrasena").asText());
        assertEquals(204,request("DELETE","/users/me",Map.of("currentPassword",password),access(logged)).status);
        assertEquals(0,json.readTree(file.toFile()).size());
        if(Files.getFileStore(file).supportsFileAttributeView("posix"))
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
    }
    void error(Reply r,int status,String code) { assertEquals(status,r.status,r.body.toString());assertEquals(code,r.body.path("error").path("code").asText());assertFalse(r.body.path("requestId").asText().isEmpty()); }
    @Test void lifecycleAndCascade() throws Exception {
        String name=name();Reply first=register(name);assertEquals(201,first.status);
        assertEquals(600,first.body.path("expiresIn").asInt());
        String id=first.body.path("user").path("id").asText();UUID.fromString(id);
        error(request("POST","/auth/login",Map.of("username"," "+name.toUpperCase(Locale.ROOT)+" ","password",PASSWORD),null),409,"SESSION_ALREADY_ACTIVE");
        assertEquals(200,request("GET","/users/me",null,access(first)).status);
        error(request("PATCH","/users/me",Map.of("username","NuevoNombre","currentPassword","otra clave equivocada"),access(first)),403,"CURRENT_PASSWORD_INVALID");
        Reply renamed=request("PATCH","/users/me",Map.of("username","NuevoNombre","currentPassword",PASSWORD),access(first));
        assertEquals(200,renamed.status);assertEquals(id,renamed.body.path("id").asText());
        error(request("POST","/auth/login",Map.of("username",name,"password",PASSWORD),null),401,"INVALID_CREDENTIALS");
        error(request("POST","/auth/login",Map.of("username","nuevonombre","password",PASSWORD),null),409,"SESSION_ALREADY_ACTIVE");
        assertEquals(204,request("POST","/auth/logout",null,access(first)).status);
        Reply second=request("POST","/auth/login",Map.of("username","nuevonombre","password",PASSWORD),null);
        assertEquals(200,second.status);
        assertEquals(204,request("DELETE","/users/me",Map.of("currentPassword",PASSWORD),access(second)).status);
        error(request("GET","/users/me",null,access(first)),401,"SESSION_INVALID");
        error(request("POST","/auth/refresh",Map.of("refreshToken",refresh(second)),null),401,"SESSION_INVALID");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sessions",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM refresh_tokens",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM deletion_ledger",Integer.class));
        assertEquals(201,register("NuevoNombre").status);
    }
    @Test void secretsAreHashedAndExcluded() throws Exception {
        Reply r=register();
        String hash=db.queryForObject("SELECT password_hash FROM users",String.class);
        assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));assertFalse(hash.contains(PASSWORD));
        assertEquals(AuthService.sha256(refresh(r)),db.queryForObject("SELECT token_hash FROM refresh_tokens",String.class));
        String user=r.body.path("user").toString();assertFalse(user.contains("hash"));assertFalse(user.contains("normalized"));
        assertEquals("no-store",r.headers.firstValue("Cache-Control").orElse(""));
        assertTrue(r.headers.firstValue("X-Request-Id").isPresent());
    }
    @Test void rotationAndReplayRevokeFamily() throws Exception {
        Reply r=register();
        Reply rotated=request("POST","/auth/refresh",Map.of("refreshToken",refresh(r)),null);
        assertEquals(200,rotated.status);assertNotEquals(refresh(r),refresh(rotated));
        assertEquals(r.body.path("refreshExpiresAt"),rotated.body.path("refreshExpiresAt"));
        error(request("POST","/auth/refresh",Map.of("refreshToken",refresh(r)),null),401,"SESSION_INVALID");
        error(request("GET","/users/me",null,access(rotated)),401,"SESSION_INVALID");
        error(request("POST","/auth/refresh",Map.of("refreshToken",refresh(rotated)),null),401,"SESSION_INVALID");
        assertNotNull(db.queryForObject("SELECT revoked_at FROM sessions",String.class));
    }
    @Test void logoutIsImmediateIdempotentAndDoesNotCloseLaterSession() throws Exception {
        Reply r=register();String username=r.body.path("user").path("username").asText();
        error(request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null),409,"SESSION_ALREADY_ACTIVE");
        assertEquals(204,request("POST","/auth/logout",null,access(r)).status);
        Reply other=request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null);
        assertEquals(200,other.status);
        assertEquals(204,request("POST","/auth/logout",null,access(r)).status);
        error(request("GET","/users/me",null,access(r)),401,"SESSION_INVALID");
        assertEquals(200,request("GET","/users/me",null,access(other)).status);
    }
    @Test void concurrentLoginAllowsExactlyOneDevice() throws Exception {
        Reply r=register();String username=r.body.path("user").path("username").asText();
        assertEquals(204,request("POST","/auth/logout",null,access(r)).status);
        Callable<Reply> login=()->request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null);
        var replies=parallel(login,login);
        assertEquals(List.of(200,409),replies.stream().map(Reply::status).sorted().toList());
        Reply winner=replies.stream().filter(x->x.status==200).findFirst().orElseThrow();
        assertEquals(200,request("GET","/users/me",null,access(winner)).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sessions WHERE revoked_at IS NULL",Integer.class));
        error(request("POST","/auth/refresh",Map.of("refreshToken",refresh(r)),null),401,"SESSION_INVALID");
    }
    @Test void presenceKeepsFirstSessionAndIdleSessionCannotRevive() throws Exception {
        Reply first=register();String username=first.body.path("user").path("username").asText();
        clock.now=clock.now.plusSeconds(44);
        assertEquals(200,request("GET","/users/me",null,access(first)).status);
        clock.now=clock.now.plusSeconds(44);
        error(request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null),409,"SESSION_ALREADY_ACTIVE");
        assertEquals(200,request("GET","/users/me",null,access(first)).status);
        clock.now=clock.now.plusSeconds(45);
        error(request("GET","/users/me",null,access(first)),401,"SESSION_INVALID");
        Reply second=request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null);
        assertEquals(200,second.status);
        error(request("GET","/users/me",null,access(first)),401,"SESSION_INVALID");
        error(request("POST","/auth/refresh",Map.of("refreshToken",refresh(first)),null),401,"SESSION_INVALID");
        assertEquals(200,request("GET","/users/me",null,access(second)).status);
    }
    @Test void failedLoginAndFailedNewSessionPreserveExistingAccount() throws Exception {
        Reply first=register();String username=first.body.path("user").path("username").asText();
        error(request("POST","/auth/login",Map.of("username",username,"password","wrong!"),null),401,"INVALID_CREDENTIALS");
        assertEquals(200,request("GET","/users/me",null,access(first)).status);
        clock.now=clock.now.plusSeconds(45);
        db.execute("CREATE TRIGGER fail_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT,'test rollback'); END");
        try { assertEquals(503,request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null).status); }
        finally { db.execute("DROP TRIGGER fail_session"); }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sessions WHERE revoked_at IS NULL",Integer.class));
        assertEquals(200,request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null).status);
    }
    @Test void expiredAccessTokenDoesNotKeepAccountLocked() throws Exception {
        Reply first=register();String username=first.body.path("user").path("username").asText();
        clock.now=clock.now.plusSeconds(599);
        db.update("UPDATE sessions SET last_seen_at=?",clock.now.toString());
        assertEquals(200,request("GET","/users/me",null,access(first)).status);
        clock.now=clock.now.plusSeconds(1);
        assertEquals(200,request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null).status);
        error(request("POST","/auth/refresh",Map.of("refreshToken",refresh(first)),null),401,"SESSION_INVALID");
    }
    @Test void concurrentRegistrationHasOneWinner() throws Exception {
        String name=name();List<Reply> results=parallel(()->register(name),()->register(name.toUpperCase(Locale.ROOT)));
        assertEquals(List.of(201,409),results.stream().map(Reply::status).sorted().toList());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sessions",Integer.class));
    }
    @Test void concurrentRotationHasOneWinnerThenRevokes() throws Exception {
        Reply r=register();Callable<Reply> renew=()->request("POST","/auth/refresh",Map.of("refreshToken",refresh(r)),null);
        var results=parallel(renew,renew);
        assertEquals(List.of(200,401),results.stream().map(Reply::status).sorted().toList());
        error(request("GET","/users/me",null,access(results.stream().filter(x->x.status==200).findFirst().orElseThrow())),401,"SESSION_INVALID");
    }
    @Test void concurrentDeleteAndRefreshCannotResurrect() throws Exception {
        Reply r=register();
        var results=parallel(()->request("DELETE","/users/me",Map.of("currentPassword",PASSWORD),access(r)),
            ()->request("POST","/auth/refresh",Map.of("refreshToken",refresh(r)),null));
        assertEquals(204,results.getFirst().status);
        assertTrue(Set.of(200,401).contains(results.get(1).status));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sessions",Integer.class));
    }
    @Test void rollbackLeavesNoPartialAccount() throws Exception {
        db.execute("CREATE TRIGGER fail_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT,'test rollback'); END");
        try { assertEquals(503,register(name()).status);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class)); }
        finally { db.execute("DROP TRIGGER fail_session"); }
    }
    @Test void rollbackDeleteRestoresAccountAndLedger() throws Exception {
        Reply r=register();db.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON users BEGIN SELECT RAISE(ABORT,'test rollback'); END");
        try {
            assertEquals(503,request("DELETE","/users/me",Map.of("currentPassword",PASSWORD),access(r)).status);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM deletion_ledger",Integer.class));
        } finally { db.execute("DROP TRIGGER fail_delete"); }
    }
    @Test void schemaAndPragmasOnMultipleConnections() throws Exception {
        assertEquals("wal",db.queryForObject("PRAGMA journal_mode",String.class));
        try(var a=db.getDataSource().getConnection();var b=db.getDataSource().getConnection()) {
            for(var c:List.of(a,b)) for(var pair:Map.of("foreign_keys",1,"synchronous",2,"busy_timeout",5000).entrySet()) {
                try(var s=c.createStatement();var r=s.executeQuery("PRAGMA "+pair.getKey())) { assertTrue(r.next());assertEquals(pair.getValue().intValue(),r.getInt(1)); }
            }
        }
    }
    @Test void validationBodyLimitAndGuestAccess() throws Exception {
        error(request("GET","/users/me",null,null),401,"UNAUTHORIZED");
        error(request("GET","/users/me",null,"guest:123"),401,"UNAUTHORIZED");
        error(request("POST","/auth/register",Map.of("username","abc","password",PASSWORD,"isGuest",true),null),400,"VALIDATION_ERROR");
        error(request("POST","/auth/register",Map.of("username","ñandú","password",PASSWORD),null),400,"VALIDATION_ERROR");
        error(request("POST","/auth/register",Map.of("username","abc","password","123456789012345"),null),400,"VALIDATION_ERROR");
        error(request("POST","/auth/register",Map.of("username","abc","password","x".repeat(129)),null),400,"VALIDATION_ERROR");
        error(request("POST","/auth/register","x".repeat(8193),null),413,"PAYLOAD_TOO_LARGE");
        error(request("POST","/auth/register","{broken",null),400,"VALIDATION_ERROR");
        assertEquals(200,request("GET","/health/ready",null,null).status);
    }
    @Test void unicodeAndSpacesNotTruncatedOrNormalized() throws Exception {
        String name=name(),password=" 🚀".repeat(15)+" ";
        Reply r=request("POST","/auth/register",Map.of("username",name,"password",password),null);assertEquals(201,r.status);
        error(request("POST","/auth/login",Map.of("username",name,"password",password.strip()),null),401,"INVALID_CREDENTIALS");
        error(request("POST","/auth/login",Map.of("username",name,"password",password),null),409,"SESSION_ALREADY_ACTIVE");
        assertEquals(204,request("POST","/auth/logout",null,access(r)).status);
        assertEquals(200,request("POST","/auth/login",Map.of("username",name,"password",password),null).status);
    }
    @Test void renameConflictKeepsIdentity() throws Exception {
        Reply a=register(),b=register();String name=b.body.path("user").path("username").asText();
        error(request("PATCH","/users/me",Map.of("username",name,"currentPassword",PASSWORD),access(a)),409,"USERNAME_UNAVAILABLE");
        assertEquals(a.body.path("user").path("username"),request("GET","/users/me",null,access(a)).body.path("username"));
    }
    @Test void failedLoginUsesGenericErrorAndLimitsName() throws Exception {
        String name=name();
        for(int i=0;i<5;i++) error(request("POST","/auth/login",Map.of("username",name,"password",PASSWORD),null),401,"INVALID_CREDENTIALS");
        Reply limited=request("POST","/auth/login",Map.of("username",name,"password",PASSWORD),null);
        error(limited,429,"RATE_LIMITED");assertTrue(Integer.parseInt(limited.headers.firstValue("Retry-After").orElse("0"))>0);
    }
    String signed(Reply r,String audience,Instant expiry) {
        String sub=r.body.path("user").path("id").asText();String sid=db.queryForObject("SELECT id FROM sessions WHERE user_id=?",String.class,sub);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
            JwtClaimsSet.builder().subject(sub).issuer("brilliant-server").audience(List.of(audience)).claim("sid",sid)
                .issuedAt(clock.instant().minusSeconds(1000)).expiresAt(expiry).build())).getTokenValue();
    }
    @Test void jwtValidatesSignatureClaimsAndExpiryBeforeClassifying() throws Exception {
        Reply r=register();String token=access(r);String[] parts=token.split("\\.");
        parts[1]=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"forged\"}".getBytes());
        error(request("GET","/users/me",null,String.join(".",parts)),401,"UNAUTHORIZED");
        error(request("GET","/users/me",null,signed(r,"wrong-audience",clock.instant().minusSeconds(1))),401,"UNAUTHORIZED");
        error(request("GET","/users/me",null,signed(r,"brilliant-game",clock.instant().minusSeconds(1))),401,"ACCESS_TOKEN_EXPIRED");
        error(request("GET","/users/me",null,signed(r,"wrong-audience",clock.instant().plusSeconds(300))),401,"UNAUTHORIZED");
        clock.now=clock.now.plusSeconds(601);
        db.update("UPDATE sessions SET last_seen_at=?",clock.now.toString());
        error(request("GET","/users/me",null,access(r)),401,"ACCESS_TOKEN_EXPIRED");
        assertEquals(200,request("POST","/auth/refresh",Map.of("refreshToken",refresh(r)),null).status);
    }
    @Test void absoluteSessionExpiryDoesNotSlide() throws Exception {
        Reply r=register();clock.now=clock.now.plus(Duration.ofDays(29));
        db.update("UPDATE sessions SET last_seen_at=?",clock.now.toString());
        Reply renew=request("POST","/auth/refresh",Map.of("refreshToken",refresh(r)),null);assertEquals(200,renew.status);
        assertEquals(r.body.path("refreshExpiresAt"),renew.body.path("refreshExpiresAt"));
        clock.now=clock.now.plus(Duration.ofDays(2));
        error(request("POST","/auth/refresh",Map.of("refreshToken",refresh(renew)),null),401,"SESSION_INVALID");
    }
    @SafeVarargs final List<Reply> parallel(Callable<Reply>... work) throws Exception {
        try(var executor=Executors.newFixedThreadPool(work.length)) {
            var gate=new CountDownLatch(1);List<Future<Reply>> futures=new ArrayList<>();
            for(var task:work) futures.add(executor.submit(()->{gate.await();return task.call();}));gate.countDown();
            List<Reply> result=new ArrayList<>();for(var future:futures) result.add(future.get(40,TimeUnit.SECONDS));return result;
        }
    }
}
