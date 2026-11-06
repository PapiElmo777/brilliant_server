package com.brilliant.server.database;

import com.brilliant.server.config.Settings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomMigrationTest {
    @TempDir Path directory;
    @Test void lobbyAndHistorySurviveReopeningWithoutChangingAccountMigrations() throws Exception {
        var settings=mock(Settings.Values.class);when(settings.directory()).thenReturn(directory);
        String firstChecksum,secondChecksum;
        try(var source=new Database().dataSource(settings,new MockEnvironment())) {
            var db=new JdbcTemplate(source);
            firstChecksum=db.queryForObject("SELECT checksum FROM schema_migrations WHERE version=1",String.class);
            secondChecksum=db.queryForObject("SELECT checksum FROM schema_migrations WHERE version=2",String.class);
            db.update("INSERT INTO users VALUES('u','Ana','ana','hash','now','now')");
            db.update("INSERT INTO rooms(id,name,host_member_id,capacity,state,revision,sequence,created_at) VALUES('room','Servidor','member',6,'LOBBY',4,8,'now')");
            db.update("INSERT INTO room_members(id,room_id,user_id,session_id,username,join_order,ready,last_seen_at) VALUES('member','room','u','sid','Ana',1,1,'now')");
        }
        try(var source=new Database().dataSource(settings,new MockEnvironment())) {
            var db=new JdbcTemplate(source);
            assertEquals(firstChecksum,db.queryForObject("SELECT checksum FROM schema_migrations WHERE version=1",String.class));
            assertEquals(secondChecksum,db.queryForObject("SELECT checksum FROM schema_migrations WHERE version=2",String.class));
            assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM schema_migrations",Integer.class));
            assertEquals("member",db.queryForObject("SELECT host_member_id FROM rooms WHERE id='room'",String.class));
            assertEquals(1,db.queryForObject("SELECT ready FROM room_members WHERE id='member'",Integer.class));
            assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("INSERT INTO room_members(id,room_id,user_id,session_id,username,join_order,last_seen_at) VALUES('duplicate','room','u','sid','Ana',2,'now')"));
            db.update("DELETE FROM users WHERE id='u'");
            assertNull(db.queryForObject("SELECT user_id FROM room_members WHERE id='member'",String.class));
            assertEquals("Ana",db.queryForObject("SELECT username FROM room_members WHERE id='member'",String.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rooms",Integer.class));
        }
    }
}
