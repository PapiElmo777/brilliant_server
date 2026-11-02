package com.brilliant.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record Login(String username,String password) {}
    public record Refresh(String refreshToken) {}
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    @PostMapping("/register") ResponseEntity<?> register(@RequestBody Login body,HttpServletRequest request) {
        return ResponseEntity.status(201).body(auth.register(body.username(),body.password(),request.getRemoteAddr()));
    }
    @PostMapping("/login") Object login(@RequestBody Login body,HttpServletRequest request) { return auth.login(body.username(),body.password(),request.getRemoteAddr()); }
    @PostMapping("/refresh") Object refresh(@RequestBody Refresh body) { return auth.refresh(body.refreshToken()); }
    @PostMapping("/logout") ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt,@RequestBody(required=false) String body) {
        if(body!=null && !body.isBlank()) throw new ApiException(400,"VALIDATION_ERROR");
        auth.logout(jwt.getSubject(),jwt.getClaimAsString("sid"));return ResponseEntity.noContent().build();
    }
}
