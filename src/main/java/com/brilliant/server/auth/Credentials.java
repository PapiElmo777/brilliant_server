package com.brilliant.server.auth;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;

@Component
public class Credentials {
    private final PasswordEncoder encoder;
    private final Set<String> common;
    private final String dummy;
    public Credentials(PasswordEncoder encoder) throws java.io.IOException {
        this.encoder=encoder;
        common=new HashSet<>(new ClassPathResource("common-passwords.txt").getContentAsString(StandardCharsets.UTF_8).lines().toList());
        dummy=encoder.encode(UUID.randomUUID().toString());
    }
    public String username(String name) {
        String trimmed = name==null ? "" : name.strip();
        if(!trimmed.matches("[A-Za-z0-9_]{3,16}"))
            throw new ApiException(400,"VALIDATION_ERROR",Map.of("username","Usa entre 3 y 16 letras ASCII, números o guion bajo."),0);
        return trimmed;
    }
    public String normalized(String name) { return name.toLowerCase(Locale.ROOT); }
    public void newPassword(String password) {
        passwordInput(password);
        if(!password.matches("(?s).*[\\p{P}\\p{S}].*"))
            throw new ApiException(400,"VALIDATION_ERROR",Map.of("password","Incluye al menos un carácter especial, como !, @ o #."),0);
        if(common.contains(password.toLowerCase(Locale.ROOT)))
            throw new ApiException(400,"VALIDATION_ERROR",Map.of("password","Elige una contraseña que no sea común."),0);
    }
    public void passwordInput(String password) {
        int n=password==null ? 0 : password.codePointCount(0,password.length());
        if(n<3 || n>128 || !wellFormed(password))
            throw new ApiException(400,"VALIDATION_ERROR",Map.of("password","Usa de 3 a 128 caracteres Unicode válidos."),0);
    }
    private boolean wellFormed(String value) {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) { if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) return false; }
            else if(Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    public String hash(String password) { return encoder.encode(password); }
    public boolean matches(String password,String hash) { return encoder.matches(password,hash==null?dummy:hash); }
}
