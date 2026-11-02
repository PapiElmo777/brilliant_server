package com.brilliant.server.database;
import java.time.Clock;
import org.springframework.scheduling.annotation.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration
@EnableScheduling
public class Maintenance {
    private final JdbcTemplate db; private final TransactionTemplate tx; private final Clock clock;
    public Maintenance(JdbcTemplate db,TransactionTemplate tx,Clock clock) { this.db=db;this.tx=tx;this.clock=clock; }
    @Scheduled(initialDelay=60000,fixedDelay=3600000)
    public void prune() {
        Database.write(tx,() -> {
            String now=clock.instant().toString();
            db.update("DELETE FROM sessions WHERE expires_at<=?",now);
            db.update("DELETE FROM deletion_ledger WHERE expires_at<=?",now);
            return null;
        });
    }
}
