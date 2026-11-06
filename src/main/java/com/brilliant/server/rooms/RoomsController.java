package com.brilliant.server.rooms;

import com.brilliant.server.auth.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomsController {
    private final RoomService rooms;
    public RoomsController(RoomService rooms) { this.rooms = rooms; }
    private String sid(Jwt jwt) { return jwt.getClaimAsString("sid"); }
    private String text(JsonNode b, String field) {
        if (!b.path(field).isTextual()) throw new ApiException(400,"VALIDATION_ERROR");
        return b.path(field).textValue();
    }
    private Integer number(JsonNode b, String field) {
        if (!b.path(field).isIntegralNumber() || !b.path(field).canConvertToInt()) throw new ApiException(400,"VALIDATION_ERROR");
        return b.path(field).intValue();
    }
    private long revision(JsonNode b) {
        if (!b.path("expectedRevision").isIntegralNumber() || !b.path("expectedRevision").canConvertToLong()) throw new ApiException(400,"VALIDATION_ERROR");
        return b.path("expectedRevision").longValue();
    }
    private String command(JsonNode b, String... fields) {
        if (b == null || !b.isObject()) throw new ApiException(400,"VALIDATION_ERROR");
        Set<String> expected = new HashSet<>(Arrays.asList(fields)); expected.add("commandId");
        Set<String> actual = new HashSet<>(); b.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new ApiException(400,"VALIDATION_ERROR");
        return text(b,"commandId");
    }
    @GetMapping Object list(@AuthenticationPrincipal Jwt jwt) { return rooms.list(jwt.getSubject(),sid(jwt)); }
    @GetMapping("/{id}") Object get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) { return rooms.get(id,jwt.getSubject(),sid(jwt)); }
    @PostMapping Object create(@RequestBody JsonNode b, @AuthenticationPrincipal Jwt jwt) {
        return rooms.create(jwt.getSubject(),sid(jwt),command(b,"name","capacity"),b,text(b,"name"),number(b,"capacity"));
    }
    @PostMapping("/{id}/join") Object join(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        return rooms.join(id,jwt.getSubject(),sid(jwt),command(b),b);
    }
    @PostMapping("/{id}/leave") Object leave(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        return rooms.leave(id,jwt.getSubject(),sid(jwt),command(b),b);
    }
    @PatchMapping("/{id}/settings") Object settings(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        return rooms.settings(id,jwt.getSubject(),sid(jwt),command(b,"expectedRevision","capacity","levelId"),b,revision(b),number(b,"capacity"),b.path("levelId").isNull() ? null : text(b,"levelId"),false);
    }
    @PostMapping("/{id}/random-level") Object random(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        return rooms.settings(id,jwt.getSubject(),sid(jwt),command(b,"expectedRevision"),b,revision(b),null,null,true);
    }
    @PutMapping("/{id}/ready") Object ready(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        String cid = command(b,"expectedRevision","ready");
        if (!b.path("ready").isBoolean()) throw new ApiException(400,"VALIDATION_ERROR");
        return rooms.ready(id,jwt.getSubject(),sid(jwt),cid,b,revision(b),b.path("ready").booleanValue());
    }
    @GetMapping("/{id}/messages") Object history(@PathVariable String id,@RequestParam(required=false) Long before,@AuthenticationPrincipal Jwt jwt) {
        return rooms.messages(id,jwt.getSubject(),sid(jwt),before);
    }
    @PostMapping("/{id}/messages") Object message(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        return rooms.message(id,jwt.getSubject(),sid(jwt),command(b,"text"),b,text(b,"text"));
    }
    @PostMapping("/{id}/start") Object start(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        return rooms.start(id,jwt.getSubject(),sid(jwt),command(b,"expectedRevision"),b,revision(b));
    }
}
