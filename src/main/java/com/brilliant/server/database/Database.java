package com.brilliant.server.database;

import com.brilliant.server.config.Settings;
import com.brilliant.server.auth.ApiException;
import com.zaxxer.hikari.*;
import org.sqlite.SQLiteConfig;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;

@Configuration
public class Database {
    @Bean(destroyMethod="close")
    @DependsOn("signingKey")
    HikariDataSource dataSource(Settings.Values settings, org.springframework.core.env.Environment env) throws Exception {
        Path dir = settings.directory();
        String url = settings.databaseUrl();
        if (url == null || url.isBlank()) url = "jdbc:sqlite:" + dir.resolve("brilliant.db");
        if (!url.startsWith("jdbc:sqlite:") || url.contains(":memory:") || url.contains("?") || url.contains("file:"))
            throw new IllegalArgumentException("DATABASE_URL debe apuntar a un archivo local SQLite");
        Path db = Path.of(url.substring("jdbc:sqlite:".length())).toAbsolutePath().normalize();
        Files.createDirectories(db.getParent());
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true); sqlite.setBusyTimeout(5000);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL); sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        // BEGIN IMMEDIATE evita promover una lectura obsoleta a escritor (SQLITE_BUSY_SNAPSHOT).
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + db); config.setDataSourceProperties(sqlite.toProperties());
        config.setMaximumPoolSize(4); config.setMinimumIdle(1); config.setConnectionTimeout(10000);
        HikariDataSource ds = new HikariDataSource(config);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            if (!"wal".equalsIgnoreCase(jdbc.queryForObject("PRAGMA journal_mode", String.class))
                    || jdbc.queryForObject("PRAGMA foreign_keys", Integer.class) != 1
                    || jdbc.queryForObject("PRAGMA synchronous", Integer.class) != 2
                    || jdbc.queryForObject("PRAGMA busy_timeout", Integer.class) != 5000)
                throw new IllegalStateException("Pragmas SQLite incorrectos");
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.executeWithoutResult(status -> {
                jdbc.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, checksum TEXT NOT NULL)");
                try {
                    var versions = jdbc.queryForList("SELECT version FROM schema_migrations", Integer.class);
                    if (versions.stream().anyMatch(v -> v != 1 && v != 2)) throw new IllegalStateException("Versión de base no soportada");
                    for (var migration : java.util.Map.of(1,"V001__accounts.sql",2,"V002__single_session.sql").entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                        String sql = new ClassPathResource("db/migration/"+migration.getValue()).getContentAsString(StandardCharsets.UTF_8);
                        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
                        if (!versions.contains(migration.getKey())) {
                            for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
                            jdbc.update("INSERT INTO schema_migrations VALUES (?, ?)",migration.getKey(),checksum);
                        } else if (!checksum.equals(jdbc.queryForObject("SELECT checksum FROM schema_migrations WHERE version=?",String.class,migration.getKey())))
                            throw new IllegalStateException("La migración aplicada fue modificada");
                    }
                } catch (Exception e) { throw new IllegalStateException("Falló la migración", e); }
            });
            if(env.getProperty("app.rotate-key",Boolean.class,false))
                tx.executeWithoutResult(status -> jdbc.update("UPDATE sessions SET revoked_at=COALESCE(revoked_at,?)",java.time.Instant.now().toString()));
            Settings.restrict(db, false);
            return ds;
        } catch (Exception e) { ds.close(); throw e; }
    }
    @Bean TransactionTemplate transactions(DataSource ds) { return new TransactionTemplate(new DataSourceTransactionManager(ds)); }

    public static <T> T write(TransactionTemplate tx, Supplier<T> work) {
        for (int attempt=0; ; attempt++) {
            try { return tx.execute(status -> work.get()); }
            catch (DataAccessException | org.springframework.transaction.TransactionException e) {
                if (!busy(e)) throw e;
                // TransactionTemplate ya terminó el rollback antes de entrar aquí.
                if (attempt == 2) throw new ApiException(503, "SERVICE_UNAVAILABLE");
                try { Thread.sleep(25L * (attempt + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ApiException(503, "SERVICE_UNAVAILABLE"); }
            }
        }
    }
    public static boolean usernameConflict(Throwable e) {
        for(Throwable t=e;t!=null;t=t.getCause())
            if(t instanceof org.sqlite.SQLiteException sql
                    && sql.getResultCode()==org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                    && sql.getMessage().contains("users.username_normalized")) return true;
        return false;
    }
    public static boolean busy(Throwable e) {
        for (Throwable t=e; t!=null; t=t.getCause())
            if (t instanceof java.sql.SQLException sql && (sql.getErrorCode()==5 || sql.getErrorCode()==6)) return true;
        return false;
    }
}
