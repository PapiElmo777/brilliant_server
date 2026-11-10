package com.brilliant.server.users;
import com.brilliant.server.auth.AuthService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/users/me")
public class UsersController {
    public record Rename(String username,String currentPassword) {}
    public record Avatar(String avatarId) {}
    @PutMapping("/avatar") Object avatar(@AuthenticationPrincipal Jwt jwt,@RequestBody Avatar body) { return auth.avatar(jwt.getSubject(),jwt.getClaimAsString("sid"),body.avatarId()); }
    public record Delete(String currentPassword) {}
    private final AuthService auth;
    public UsersController(AuthService auth) { this.auth=auth; }
    @GetMapping Object me(@AuthenticationPrincipal Jwt jwt) { return auth.me(jwt.getSubject(),jwt.getClaimAsString("sid")); }
    @PatchMapping Object rename(@AuthenticationPrincipal Jwt jwt,@RequestBody Rename body) { return auth.rename(jwt.getSubject(),jwt.getClaimAsString("sid"),body.username(),body.currentPassword()); }
    @DeleteMapping ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,@RequestBody Delete body) {
        auth.delete(jwt.getSubject(),jwt.getClaimAsString("sid"),body.currentPassword());return ResponseEntity.noContent().build();
    }
}
