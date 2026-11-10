package com.brilliant.server.database;

import com.brilliant.server.config.Settings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RematchMigrationTest {
    @TempDir Path directory;
    @Test void preservesV006MatchAndAssignsTheParticipantView() throws Exception {
        var checksums=new ArrayList<String>();
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("brilliant.db"));var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
            var files=List.of("V001__accounts.sql","V002__single_session.sql","V003__rooms.sql","V004__room_channels_and_chat.sql","V005__match_rounds.sql","V006__match_scoring.sql");
            for(int i=0;i<files.size();i++) {
                String sql=new ClassPathResource("db/migration/"+files.get(i)).getContentAsString(StandardCharsets.UTF_8);
                for(String part:sql.split(";")) if(!part.isBlank()) statement.execute(part);
                String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));checksums.add(checksum);
                statement.execute("INSERT INTO schema_migrations VALUES("+(i+1)+",'"+checksum+"')");
            }
            statement.execute("INSERT INTO users VALUES('u','Ana','ana','hash','now','now')");
            statement.execute("INSERT INTO rooms(id,name,host_member_id,capacity,level_id,state,created_at) VALUES('room','Sala','member',6,'tablero_01','PREPARING','now')");
            statement.execute("INSERT INTO room_members(id,room_id,user_id,session_id,username,join_order,last_seen_at) VALUES('member','room','u','sid','Ana',1,'now')");
            statement.execute("INSERT INTO room_matches VALUES('match','room','tablero_01',1,26,'PREPARING','[{\"memberId\":\"member\"}]','now')");
            statement.execute("INSERT INTO match_progress VALUES('match',0,NULL)");
            statement.execute("INSERT INTO match_players VALUES('match','member','{\"f1_c2\":1}','2026-11-08T00:00:00Z')");
            statement.execute("INSERT INTO match_rounds VALUES('round','match',1,3,4,'2026-11-08T00:00:00Z','2026-11-08T00:01:00Z',NULL)");
            statement.execute("INSERT INTO round_actions VALUES('round','member','SKIP',NULL,NULL,'PLAYER','2026-11-08T00:00:01Z')");

        }
        var settings=mock(Settings.Values.class);when(settings.directory()).thenReturn(directory);
        for(int run=0;run<2;run++) try(var source=new Database().dataSource(settings,new MockEnvironment())) {
            var db=new JdbcTemplate(source);assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM schema_migrations",Integer.class));
            for(int i=0;i<checksums.size();i++) assertEquals(checksums.get(i),db.queryForObject("SELECT checksum FROM schema_migrations WHERE version=?",String.class,i+1));
            assertEquals("{\"f1_c2\":1}",db.queryForObject("SELECT board_json FROM match_players WHERE match_id='match' AND member_id='member'",String.class));
            assertNotNull(db.queryForObject("SELECT prepared_at FROM match_players WHERE match_id='match'",String.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM round_actions WHERE round_id='round'",Integer.class));
            assertEquals(0,db.queryForObject("SELECT blocked_rounds FROM match_progress WHERE match_id='match'",Integer.class));
            assertNull(db.queryForObject("SELECT result_json FROM match_progress WHERE match_id='match'",String.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM zone_claims",Integer.class));
            assertEquals("match",db.queryForObject("SELECT view_match_id FROM room_members WHERE id='member'",String.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rematch_acceptances",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM room_member_events",Integer.class));
            assertEquals(26,db.queryForObject("SELECT target FROM room_matches WHERE id='match'",Integer.class));
        }
    }
}
