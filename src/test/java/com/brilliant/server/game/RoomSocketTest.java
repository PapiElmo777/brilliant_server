package com.brilliant.server.game;

import com.brilliant.server.rooms.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomSocketTest {
    @Test void slowSocketDoesNotBlockAnotherParticipant() throws Exception {
        var rooms=mock(RoomService.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var delivered=new CountDownLatch(1);
        var first=socket("first","a");var second=socket("second","b");
        var snapshot=Map.<String,Object>of("sequence",0,"serverTime","2026-11-06T00:00:00Z");
        when(rooms.bindChannel(anyString(),anyString(),anyString(),anyString())).thenReturn(snapshot);
        when(rooms.channelEvents(anyString(),anyString(),anyString(),anyString(),anyLong())).thenReturn(List.of());
        doAnswer(i->{entered.countDown();release.await(5,TimeUnit.SECONDS);return null;}).when(first).sendMessage(any());
        doAnswer(i->{delivered.countDown();return null;}).when(second).sendMessage(any());
        var handler=new RoomSocket(rooms,new ObjectMapper(),Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"),ZoneOffset.UTC));
        try {
            handler.afterConnectionEstablished(first);assertTrue(entered.await(2,TimeUnit.SECONDS));
            handler.afterConnectionEstablished(second);assertTrue(delivered.await(2,TimeUnit.SECONDS));
        } finally {release.countDown();handler.stop();}
    }
    private WebSocketSession socket(String id,String user) {
        var session=mock(WebSocketSession.class);
        var jwt=Jwt.withTokenValue("test").header("alg","RS256").subject(user).claim("sid","sid")
            .issuedAt(Instant.parse("2026-11-06T00:00:00Z")).expiresAt(Instant.parse("2026-11-06T01:00:00Z")).build();
        when(session.getAttributes()).thenReturn(Map.of("jwt",jwt,"roomId","room"));
        when(session.getId()).thenReturn(id);when(session.isOpen()).thenReturn(true);return session;
    }
}
