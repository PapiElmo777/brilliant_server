package com.brilliant.server.users;

import com.brilliant.server.auth.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Accounts {
    public static final long IDLE_SECONDS = 600;
    public record User(String id,String username,String createdAt,String updatedAt,String avatarId) { public User(String id,String username,String createdAt,String updatedAt) {this(id,username,createdAt,updatedAt,null);} }
    public record Stored(User user,String passwordHash) {}
    private final JdbcTemplate db;
    private final Clock clock;
    public Accounts(JdbcTemplate db, Clock clock) { this.db=db; this.clock=clock; }
    public Stored byName(String name) { return find("username_normalized",name); }
    public Stored byId(String id) { return find("id",id); }
    private Stored find(String column,String value) {
        var rows=db.query("SELECT * FROM users WHERE "+column+"=?", (r,n) -> new Stored(
            new User(r.getString("id"),r.getString("username"),r.getString("created_at"),r.getString("updated_at"),r.getString("avatar_id")),r.getString("password_hash")),value);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Stored requireActive(String user,String session) {
        if(!active(user,session,false)) throw new ApiException(401,"SESSION_INVALID");
        Stored stored=byId(user);
        if(stored==null) throw new ApiException(401,"SESSION_INVALID");
        return stored;
    }
    public boolean active(String user,String session,boolean allowRevoked) {
        var rows=db.queryForList("SELECT s.expires_at, s.revoked_at,s.last_seen_at FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.id=? AND u.id=?",session,user);
        return rows.size()==1 && Instant.parse((String)rows.getFirst().get("expires_at")).isAfter(clock.instant())
            && (allowRevoked || (rows.getFirst().get("revoked_at")==null
                && Instant.parse((String)rows.getFirst().get("last_seen_at")).plusSeconds(IDLE_SECONDS).isAfter(clock.instant())));
    }
}
