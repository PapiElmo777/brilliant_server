package com.brilliant.server.health;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
    private final JdbcTemplate db;
    public HealthController(JdbcTemplate db) { this.db=db; }
    @GetMapping("/live") Object live() { return Map.of("status","UP"); }
    @GetMapping("/ready") Object ready() {
        db.queryForObject("SELECT COUNT(*) FROM schema_migrations",Integer.class);
        return Map.of("status","UP");
    }
}
