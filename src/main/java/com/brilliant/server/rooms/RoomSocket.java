package com.brilliant.server.rooms;

import com.brilliant.server.auth.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class RoomSocket extends TextWebSocketHandler implements HandshakeInterceptor {
    private final RoomService rooms;
    private final ObjectMapper json;
    private final Clock clock;
    private final ConcurrentMap<String,Connection> connections = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),
        r -> { var t = new Thread(r,"room-events"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
    private static final class Connection {
        final WebSocketSession socket;
        final Jwt jwt;
        final String roomId, user, sid;
        final AtomicBoolean pumping = new AtomicBoolean();
        volatile Map<String,Object> initial;
        volatile long sequence;
        volatile boolean pong, closed;
        Connection(WebSocketSession socket,Jwt jwt,String roomId,Map<String,Object> initial) {
            this.socket = new ConcurrentWebSocketSessionDecorator(socket,1500,131072);
            this.jwt=jwt;this.roomId=roomId;this.user=jwt.getSubject();this.sid=jwt.getClaimAsString("sid");this.initial=initial;
            sequence=((Number)initial.get("sequence")).longValue();
        }
    }
    public RoomSocket(RoomService rooms,ObjectMapper json,Clock clock) { this.rooms=rooms;this.json=json;this.clock=clock; }
    @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attrs) throws Exception {
        try {
            if (request.getURI().getRawQuery()!=null) throw new ApiException(400,"VALIDATION_ERROR");
            if (!(request.getPrincipal() instanceof JwtAuthenticationToken auth)) throw new ApiException(401,"UNAUTHORIZED");
            Jwt jwt=auth.getToken();
            if (jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(clock.instant())) throw new ApiException(401,"ACCESS_TOKEN_EXPIRED");
            String[] path=request.getURI().getPath().split("/");
            if (path.length!=6) throw new ApiException(404,"ROOM_NOT_FOUND");
            String roomId=path[4];
            rooms.authorizeChannel(roomId,jwt.getSubject(),jwt.getClaimAsString("sid"));
            attrs.put("jwt",jwt);attrs.put("roomId",roomId);return true;
        } catch(ApiException e) {
            response.setStatusCode(HttpStatus.valueOf(e.status));
            response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            json.writeValue(response.getBody(),Map.of("error",Map.of("code",e.code)));return false;
        }
    }
    @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception ex) {}
    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if(session instanceof NativeWebSocketSession nativeSocket) {
            var nativeSession=nativeSocket.getNativeSession(jakarta.websocket.Session.class);
            if(nativeSession!=null) {
                nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT",1500L);
                nativeSession.getUserProperties().put("org.apache.tomcat.websocket.SESSION_CLOSE_TIMEOUT",1500L);
            }
        }
        Jwt jwt=(Jwt)session.getAttributes().get("jwt");String roomId=(String)session.getAttributes().get("roomId");
        try {
            var initial=rooms.bindChannel(roomId,jwt.getSubject(),jwt.getClaimAsString("sid"),session.getId());
            var connection=new Connection(session,jwt,roomId,initial);
            connections.put(session.getId(),connection);
            // Replaced sockets are revalidated by pump(), so an old close cannot unbind the new one.
            dispatch(connection);
        } catch(ApiException e) { session.close(new CloseStatus(e.status==401?4001:4003)); }
    }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception {
        var c=connections.get(session.getId());if(c==null || c.closed) return;
        try {
            var body=json.readTree(message.getPayload());
            if(body==null || !body.isObject() || body.size()!=1 || !"PING".equals(body.path("type").asText())) {close(c,1008);return;}
            if(!c.jwt.getExpiresAt().isAfter(clock.instant())) {close(c,4001);return;}
            rooms.channelPing(c.roomId,c.user,c.sid,c.socket.getId());c.pong=true;dispatch(c);
        } catch(ApiException e) {close(c,e.code.equals("CHANNEL_REPLACED")?4009:e.status==401?4001:4003);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {close(c,1008);}
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) {
        var c=connections.remove(session.getId());
        if(c!=null) {c.closed=true;rooms.disconnectChannel(c.roomId,c.user,session.getId());}
    }
    @Override public void handleTransportError(WebSocketSession session,Throwable error) {var c=connections.get(session.getId());if(c!=null) close(c,1011);}
    @Scheduled(initialDelay=250,fixedDelay=250) public void tick() { for(var c:connections.values()) dispatch(c); }
    private void dispatch(Connection c) {
        if(c.closed || !c.pumping.compareAndSet(false,true)) return;
        try {workers.execute(() -> {
            try {pump(c);} catch(ApiException e) {close(c,e.code.equals("CHANNEL_REPLACED")?4009:e.status==401?4001:4003);}
            catch(Exception e) {close(c,1011);} finally {c.pumping.set(false);}
        });} catch(RejectedExecutionException e) {c.pumping.set(false);close(c,1013);}
    }
    private void pump(Connection c) throws Exception {
        if(c.closed || !c.socket.isOpen()) return;
        if(!c.jwt.getExpiresAt().isAfter(clock.instant())) {close(c,4001);return;}
        // Authorization is checked before every batch, including the initial snapshot.
        var events=rooms.channelEvents(c.roomId,c.user,c.sid,c.socket.getId(),c.sequence);
        if(c.initial!=null) {
            var initial=c.initial;c.initial=null;
            send(c,Map.of("protocolVersion",1,"roomId",c.roomId,"sequence",initial.get("sequence"),"type","SNAPSHOT","serverTime",initial.get("serverTime"),"data",initial));
        }
        for(var event:events) {
            if(c.closed) return;
            send(c,event);c.sequence=((Number)event.get("sequence")).longValue();
        }
        if(c.pong) {c.pong=false;send(c,Map.of("type","PONG","serverTime",clock.instant().toString()));}
    }
    private void send(Connection c,Object frame) throws Exception {
        String payload=json.writeValueAsString(frame);
        if(payload.length()>65536) {close(c,1009);return;}
        c.socket.sendMessage(new TextMessage(payload));
    }
    private void close(Connection c,int code) {
        if(c.closed) return;c.closed=true;
        connections.remove(c.socket.getId(),c);
        try {c.socket.close(new CloseStatus(code));} catch(Exception ignored) {}
        rooms.disconnectChannel(c.roomId,c.user,c.socket.getId());
    }
    @PreDestroy public void stop() {for(var c:connections.values()) close(c,1001);workers.shutdownNow();}
}
