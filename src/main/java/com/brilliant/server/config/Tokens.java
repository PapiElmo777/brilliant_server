package com.brilliant.server.config;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class Tokens {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new Argon2PasswordEncoder(16,32,1,19456,2); }
    @Bean @DependsOn("dataLock") RSAKey signingKey(Settings.Values settings, org.springframework.core.env.Environment env) throws Exception {
        Path path = settings.directory().resolve("signing.jwk");
        boolean rotate=env.getProperty("app.rotate-key",Boolean.class,false);
        if (rotate || !Files.exists(path)) {
            Path db = settings.databaseUrl()==null || settings.databaseUrl().isBlank()
                ? settings.directory().resolve("brilliant.db")
                : Path.of(settings.databaseUrl().substring("jdbc:sqlite:".length())).toAbsolutePath();
            if (!rotate && Files.exists(db)) throw new IllegalStateException("Falta signing.jwk para una base existente. Restaura la clave o sigue la rotación documentada.");
            RSAKey generated = new RSAKeyGenerator(3072).keyID(UUID.randomUUID().toString()).generate();
            // CREATE_NEW y permisos desde creación: no sobrescribir otra clave ni publicar un archivo parcial.
            Path temp=Files.createTempFile(settings.directory(),"key-",".tmp");
            try {
                Settings.restrict(temp,false);
                Files.writeString(temp,generated.toJSONString());
                Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
        Settings.restrict(path,false);
        RSAKey key=RSAKey.parse(Files.readString(path));
        if(!key.isPrivate() || key.size()<3072 || key.getKeyID()==null) throw new IllegalStateException("Clave RSA inválida");
        return key;
    }
    @Bean JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }
    @Bean JwtDecoder jwtDecoder(RSAKey signingKey, Settings.Values settings, Clock clock) throws Exception {
        NimbusJwtDecoder decoder=NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(jwt -> {
            // El decoder ya verificó firma y algoritmo. Validar claims antes de clasificar caducidad.
            boolean identity=true;
            try { UUID.fromString(jwt.getSubject()); UUID.fromString(jwt.getClaimAsString("sid")); }
            catch(RuntimeException e) { identity=false; }
            if(!identity || !settings.issuer().equals(jwt.getClaimAsString("iss")) || (jwt.getAudience()==null || !jwt.getAudience().contains(settings.audience()))
                    || jwt.getExpiresAt()==null || jwt.getIssuedAt()==null || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
                    || (jwt.getNotBefore()!=null && jwt.getNotBefore().isAfter(clock.instant()))
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt()))
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            if(!jwt.getExpiresAt().isAfter(clock.instant()))
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("access_token_expired"));
            return OAuth2TokenValidatorResult.success();
        });
        return decoder;
    }
}
