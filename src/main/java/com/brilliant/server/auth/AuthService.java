package com.brilliant.server.auth;

import com.brilliant.server.config.Settings;
import com.brilliant.server.database.Database;
import com.brilliant.server.users.Accounts;
import com.brilliant.server.users.Accounts.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    public record AuthSession(User user,String accessToken,String tokenType,long expiresIn,String refreshToken,String refreshExpiresAt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Accounts accounts;
    private final Credentials credentials;
    private final Settings.Values settings;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final RateLimits limits;
    private final TestAccountsFile testAccounts;
    private final SecureRandom random=new SecureRandom();
    public AuthService(JdbcTemplate db,TransactionTemplate tx,Accounts accounts,Credentials credentials,Settings.Values settings,Clock clock,JwtEncoder encoder,RateLimits limits,TestAccountsFile testAccounts) {
        this.db=db;this.tx=tx;this.accounts=accounts;this.credentials=credentials;this.settings=settings;this.clock=clock;this.encoder=encoder;this.limits=limits;
        this.testAccounts=testAccounts;
    }
    public AuthSession register(String name,String password,String ip) {
        limits.hit("register:"+ip,settings.registerIpLimit(),Duration.ofHours(1));
        String username=credentials.username(name);credentials.newPassword(password);
        String hash=credentials.hash(password); // Argon2 fuera de la escritura
        try {
            AuthSession result=Database.write(tx,() -> {
                String id=UUID.randomUUID().toString(),now=clock.instant().toString();
                db.update("INSERT INTO users VALUES (?,?,?,?,?,?)",id,username,credentials.normalized(username),hash,now,now);
                return start(accounts.byId(id));
            });
            testAccounts.remember(result.user().id(),password);
            return result;
        } catch(org.springframework.dao.DataAccessException e) { if(Database.usernameConflict(e)) throw new ApiException(409,"USERNAME_UNAVAILABLE"); throw e; }
    }
    public AuthSession login(String name,String password,String ip) {
        limits.hit("login-ip:"+ip,settings.loginIpLimit(),Duration.ofMinutes(1));
        String username=credentials.username(name);credentials.passwordInput(password);
        String key="login-name:"+credentials.normalized(username);
        // Reservar antes de Argon2 evita que intentos paralelos eludan el límite de fallos.
        limits.hit(key,settings.loginNameLimit(),Duration.ofMinutes(15));
        Stored stored=accounts.byName(credentials.normalized(username));
        if(!credentials.matches(password,stored==null?null:stored.passwordHash())) throw new ApiException(401,"INVALID_CREDENTIALS");
        limits.refund(key);
        AuthSession result=Database.write(tx,() -> {
            Stored current=accounts.byId(stored.user().id());
            if(current==null || !current.passwordHash().equals(stored.passwordHash())
                    || !credentials.normalized(current.user().username()).equals(credentials.normalized(username)))
                throw new ApiException(401,"INVALID_CREDENTIALS");
            return start(current);
        });
        testAccounts.remember(result.user().id(),password);
        return result;
    }
    private AuthSession start(Stored user) {
        String sid=UUID.randomUUID().toString(); Instant now=clock.instant(),expires=now.plusSeconds(settings.sessionSeconds());
        var sessions=db.queryForList("SELECT expires_at,last_seen_at,(SELECT r.created_at FROM refresh_tokens r WHERE r.session_id=s.id ORDER BY r.rowid DESC LIMIT 1) AS token_issued_at FROM sessions s WHERE user_id=? AND revoked_at IS NULL",user.user().id());
        if(sessions.stream().anyMatch(row -> Instant.parse((String)row.get("expires_at")).isAfter(now)
                && Instant.parse((String)row.get("last_seen_at")).plusSeconds(Accounts.IDLE_SECONDS).isAfter(now)
                && row.get("token_issued_at")!=null
                && Instant.parse((String)row.get("token_issued_at")).plusSeconds(settings.accessSeconds()).isAfter(now)))
            throw new ApiException(409,"SESSION_ALREADY_ACTIVE");
        db.update("UPDATE sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",now.toString(),user.user().id());
        db.update("INSERT INTO sessions(id,user_id,created_at,expires_at,revoked_at,last_seen_at) VALUES (?,?,?,?,NULL,?)",sid,user.user().id(),now.toString(),expires.toString(),now.toString());
        return issue(user.user(),sid,expires);
    }
    private AuthSession issue(User user,String sid,Instant expires) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now=clock.instant();
        db.update("INSERT INTO refresh_tokens VALUES (?,?,?,?,?,NULL)",UUID.randomUUID().toString(),sid,sha256(secret),now.toString(),expires.toString());
        long ttl=Math.min(settings.accessSeconds(),Duration.between(now,expires).toSeconds());
        if(ttl<1) throw new ApiException(401,"SESSION_INVALID");
        var claims=JwtClaimsSet.builder().subject(user.id()).claim("sid",sid).issuer(settings.issuer()).audience(List.of(settings.audience()))
            .issuedAt(now).expiresAt(now.plusSeconds(ttl)).build();
        String jwt=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
        return new AuthSession(user,jwt,"Bearer",ttl,secret,expires.toString());
    }
    public AuthSession refresh(String secret) {
        if(secret==null || !secret.matches("[A-Za-z0-9_-]{43}")) throw new ApiException(401,"SESSION_INVALID");
        // No arrojar dentro de la transacción cuando se detecta replay: su revocación debe confirmar.
        AuthSession result=Database.write(tx,() -> {
            var rows=db.queryForList("SELECT r.*,s.user_id,s.revoked_at,s.expires_at AS session_expiry FROM refresh_tokens r JOIN sessions s ON s.id=r.session_id JOIN users u ON u.id=s.user_id WHERE r.token_hash=?",sha256(secret));
            if(rows.isEmpty()) return null;
            var row=rows.getFirst();String sid=(String)row.get("session_id");
            Instant expires=Instant.parse((String)row.get("session_expiry"));
            if(row.get("revoked_at")!=null || !expires.isAfter(clock.instant())) return null;
            if(row.get("consumed_at")!=null) { revoke(sid);return null; }
            limits.hit("refresh:"+sid,settings.refreshLimit(),Duration.ofMinutes(1));
            int count=db.update("UPDATE refresh_tokens SET consumed_at=? WHERE id=? AND consumed_at IS NULL",clock.instant().toString(),row.get("id"));
            if(count!=1) { revoke(sid);return null; }
            return issue(accounts.requireActive((String)row.get("user_id"),sid).user(),sid,expires);
        });
        if(result==null) throw new ApiException(401,"SESSION_INVALID");
        return result;
    }
    public void logout(String user,String sid) {
        Database.write(tx,() -> {
            if(!accounts.active(user,sid,true)) throw new ApiException(401,"SESSION_INVALID");
            revoke(sid); return null;
        });
    }
    private void revoke(String sid) { db.update("UPDATE sessions SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",clock.instant().toString(),sid); }
    public User me(String user,String sid) {
        return Database.write(tx,() -> {
            User current=accounts.requireActive(user,sid).user();
            db.update("UPDATE sessions SET last_seen_at=? WHERE id=? AND revoked_at IS NULL",clock.instant().toString(),sid);
            return current;
        });
    }
    private Stored verify(String user,String sid,String password) {
        limits.hit("account:"+user,settings.accountLimit(),Duration.ofMinutes(15));
        credentials.passwordInput(password);
        Stored stored=accounts.requireActive(user,sid);
        if(!credentials.matches(password,stored.passwordHash())) throw new ApiException(403,"CURRENT_PASSWORD_INVALID");
        return stored;
    }
    public User rename(String user,String sid,String name,String password) {
        String username=credentials.username(name);Stored verified=verify(user,sid,password);
        try {
            User result=Database.write(tx,() -> {
                Stored current=accounts.requireActive(user,sid);
                if(!current.passwordHash().equals(verified.passwordHash())) throw new ApiException(401,"SESSION_INVALID");
                db.update("UPDATE users SET username=?,username_normalized=?,updated_at=? WHERE id=?",username,credentials.normalized(username),clock.instant().toString(),user);
                return accounts.byId(user).user();
            });
            testAccounts.remember(user,password);
            return result;
        } catch(org.springframework.dao.DataAccessException e) { if(Database.usernameConflict(e)) throw new ApiException(409,"USERNAME_UNAVAILABLE"); throw e; }
    }
    public void delete(String user,String sid,String password) {
        Stored verified=verify(user,sid,password);
        Database.write(tx,() -> {
            Stored current=accounts.requireActive(user,sid);
            if(!current.passwordHash().equals(verified.passwordHash())) throw new ApiException(401,"SESSION_INVALID");
            Instant now=clock.instant();
            db.update("INSERT INTO deletion_ledger VALUES (?,?,?)",user,now.toString(),now.plus(Duration.ofDays(30)).toString());
            if(db.update("DELETE FROM users WHERE id=?",user)!=1) throw new ApiException(401,"SESSION_INVALID");
            return null;
        });
        testAccounts.forget(user);
    }
    public static String sha256(String secret) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
