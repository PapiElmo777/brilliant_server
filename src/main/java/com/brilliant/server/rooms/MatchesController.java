package com.brilliant.server.rooms;

import com.brilliant.server.auth.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/matches")
public class MatchesController {
    private final RoomService rooms;
    public MatchesController(RoomService rooms) {this.rooms=rooms;}
    private void fields(JsonNode b,Set<String> required) {
        if(b==null || !b.isObject()) throw new ApiException(400,"VALIDATION_ERROR");
        var actual=new HashSet<String>();b.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(required)) throw new ApiException(400,"VALIDATION_ERROR");
    }
    private String text(JsonNode b,String field) {
        if(b==null || !b.path(field).isTextual()) throw new ApiException(400,"VALIDATION_ERROR");return b.path(field).textValue();
    }
    @PutMapping("/{id}/preparation") Object prepare(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        fields(b,Set.of("commandId","placements"));
        var placements=b.path("placements");if(!placements.isObject() || placements.size()!=6) throw new ApiException(400,"INVALID_PREPARATION");
        var values=new LinkedHashMap<String,Integer>();
        placements.fields().forEachRemaining(e->{if(!e.getValue().isIntegralNumber() || !e.getValue().canConvertToInt()) throw new ApiException(400,"INVALID_PREPARATION");values.put(e.getKey(),e.getValue().intValue());});
        return rooms.prepare(id,jwt.getSubject(),jwt.getClaimAsString("sid"),text(b,"commandId"),b,values);
    }
    @PostMapping("/{id}/rounds/{roundId}/actions") Object action(@PathVariable String id,@PathVariable String roundId,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        String kind=text(b,"kind");
        fields(b,"PLACE".equals(kind)?Set.of("commandId","kind","dieIndex","cellId"):Set.of("commandId","kind"));
        Integer die=null;String cell=null;
        if("PLACE".equals(kind)) {
            if(!b.path("dieIndex").isIntegralNumber() || !b.path("dieIndex").canConvertToInt()) throw new ApiException(400,"VALIDATION_ERROR");
            die=b.path("dieIndex").intValue();cell=text(b,"cellId");
        } else if(!"SKIP".equals(kind)) throw new ApiException(400,"VALIDATION_ERROR");
        return rooms.action(id,roundId,jwt.getSubject(),jwt.getClaimAsString("sid"),text(b,"commandId"),b,kind,die,cell);
    }
    @GetMapping("/{id}/end-vote") Object vote(@PathVariable String id,@AuthenticationPrincipal Jwt jwt) {
        return rooms.vote(id,jwt.getSubject(),jwt.getClaimAsString("sid"));
    }
    @PutMapping("/{id}/end-vote/ballot") Object ballot(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        fields(b,Set.of("commandId","voteId","approve"));
        if(!b.path("approve").isBoolean()) throw new ApiException(400,"VALIDATION_ERROR");
        return rooms.ballot(id,jwt.getSubject(),jwt.getClaimAsString("sid"),text(b,"commandId"),b,text(b,"voteId"),b.path("approve").booleanValue());
    }
    @PostMapping("/{id}/rematch") Object rematch(@PathVariable String id,@RequestBody JsonNode b,@AuthenticationPrincipal Jwt jwt) {
        fields(b,Set.of("commandId"));return rooms.rematch(id,jwt.getSubject(),jwt.getClaimAsString("sid"),text(b,"commandId"),b);
    }
}
