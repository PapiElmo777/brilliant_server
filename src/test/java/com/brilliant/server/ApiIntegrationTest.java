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
    @BeforeEach void clear() {
        clock.now=Instant.parse("2026-11-02T00:00:00Z");
        db.update("DELETE FROM users");db.update("DELETE FROM deletion_ledger");
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
    void error(Reply r,int status,String code) { assertEquals(status,r.status,r.body.toString());assertEquals(code,r.body.path("error").path("code").asText());assertFalse(r.body.path("requestId").asText().isEmpty()); }
    @Test void lifecycleAndCascade() throws Exception {
        String name=name();Reply first=register(name);assertEquals(201,first.status);
        assertEquals(600,first.body.path("expiresIn").asInt());
        String id=first.body.path("user").path("id").asText();UUID.fromString(id);
        assertEquals(200,request("GET","/users/me",null,access(first)).status);
        Reply second=request("POST","/auth/login",Map.of("username"," "+name.toUpperCase(Locale.ROOT)+" ","password",PASSWORD),null);
        assertEquals(200,second.status);
        error(request("PATCH","/users/me",Map.of("username","NuevoNombre","currentPassword","otra clave equivocada"),access(first)),403,"CURRENT_PASSWORD_INVALID");
        Reply renamed=request("PATCH","/users/me",Map.of("username","NuevoNombre","currentPassword",PASSWORD),access(first));
        assertEquals(200,renamed.status);assertEquals(id,renamed.body.path("id").asText());
        assertEquals("NuevoNombre",request("GET","/users/me",null,access(second)).body.path("username").asText());
        error(request("POST","/auth/login",Map.of("username",name,"password",PASSWORD),null),401,"INVALID_CREDENTIALS");
        assertEquals(200,request("POST","/auth/login",Map.of("username","nuevonombre","password",PASSWORD),null).status);
        assertEquals(204,request("DELETE","/users/me",Map.of("currentPassword",PASSWORD),access(first)).status);
        error(request("GET","/users/me",null,access(second)),401,"SESSION_INVALID");
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
    @Test void logoutIsImmediateIdempotentAndSessionScoped() throws Exception {
        Reply r=register();String username=r.body.path("user").path("username").asText();
        Reply other=request("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null);
        assertEquals(204,request("POST","/auth/logout",null,access(r)).status);
        assertEquals(204,request("POST","/auth/logout",null,access(r)).status);
        error(request("GET","/users/me",null,access(r)),401,"SESSION_INVALID");
        assertEquals(200,request("GET","/users/me",null,access(other)).status);
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
        error(request("GET","/users/me",null,access(r)),401,"ACCESS_TOKEN_EXPIRED");
        assertEquals(200,request("POST","/auth/refresh",Map.of("refreshToken",refresh(r)),null).status);
    }
    @Test void absoluteSessionExpiryDoesNotSlide() throws Exception {
        Reply r=register();clock.now=clock.now.plus(Duration.ofDays(29));
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
