package com.brilliant.server.database;

import com.brilliant.server.config.Settings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionMigrationTest {
    @TempDir Path directory;

    @Test void existingDatabaseKeepsFirstSessionAndEnforcesUniqueSessionOnRestart() throws Exception {
        String sql=new ClassPathResource("db/migration/V001__accounts.sql").getContentAsString(StandardCharsets.UTF_8);
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("brilliant.db"));var statement=connection.createStatement()) {
            for(String part:sql.split(";")) if(!part.isBlank()) statement.execute(part);
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
            statement.execute("INSERT INTO schema_migrations VALUES(1,'"+checksum+"')");
            statement.execute("INSERT INTO users VALUES('u','Piloto','piloto','hash','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            statement.execute("INSERT INTO sessions VALUES('first','u','2026-01-01T00:00:00Z','2027-01-01T00:00:00Z',NULL)");
            statement.execute("INSERT INTO sessions VALUES('second','u','2026-01-02T00:00:00Z','2027-01-02T00:00:00Z',NULL)");
        }
        var settings=mock(Settings.Values.class);
        when(settings.directory()).thenReturn(directory);
        for(int run=0;run<2;run++) {
            try(var source=new Database().dataSource(settings,new MockEnvironment())) {
                var db=new JdbcTemplate(source);
                assertEquals(7,db.queryForObject("SELECT COUNT(*) FROM schema_migrations",Integer.class));
                assertEquals("first",db.queryForObject("SELECT id FROM sessions WHERE revoked_at IS NULL",String.class));
                assertEquals("2026-01-01T00:00:00Z",db.queryForObject("SELECT last_seen_at FROM sessions WHERE id='first'",String.class));
                var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("INSERT INTO sessions(id,user_id,created_at,expires_at,last_seen_at) VALUES('third','u','now','later','now')"));
                var cause=assertInstanceOf(org.sqlite.SQLiteException.class,failure.getCause());
                assertEquals(org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE,cause.getResultCode());
            }
        }
    }
}
