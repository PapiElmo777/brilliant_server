package com.brilliant.server;

import com.brilliant.server.auth.TestAccountsFile;
import com.brilliant.server.config.Settings;
import com.brilliant.server.users.Accounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestAccountsFileTest {
    @TempDir Path directory;

    @Test void disabledByDefaultDoesNotReadOrWriteCredentials() throws Exception {
        var settings=mock(Settings.Values.class);
        var accounts=mock(Accounts.class);
        var file=new TestAccountsFile(settings,new MockEnvironment(),new ObjectMapper(),accounts);
        file.remember("id","a!2");file.forget("id");
        verifyNoInteractions(accounts);
        verify(settings,never()).directory();
    }

    @Test void refusesProductionBeforeCreatingFile() {
        var settings=mock(Settings.Values.class);
        when(settings.production()).thenReturn(true);
        assertThrows(IllegalStateException.class,()->new TestAccountsFile(settings,
            new MockEnvironment().withProperty("app.record-test-accounts","true"),new ObjectMapper(),mock(Accounts.class)));
        verify(settings,never()).directory();
    }

    @Test void concurrentWritesKeepAllAccountsWithoutPartialJson() throws Exception {
        var settings=mock(Settings.Values.class);when(settings.directory()).thenReturn(directory);
        var accounts=mock(Accounts.class);
        for(int i=0;i<4;i++) when(accounts.byId("id"+i)).thenReturn(new Accounts.Stored(new Accounts.User("id"+i,"Piloto"+i,"now","now"),"hash"));
        var file=new TestAccountsFile(settings,new MockEnvironment().withProperty("app.record-test-accounts","true"),new ObjectMapper(),accounts);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<4;i++) { final int id=i;futures.add(pool.submit(()->file.remember("id"+id,"a!"+id))); }
            for(var future:futures) future.get();
        }
        var entries=new ObjectMapper().readTree(directory.resolve("cuentas-prueba.json").toFile());
        assertEquals(4,entries.size());
        for(int i=0;i<4;i++) assertEquals("a!"+i,entries.path("id"+i).path("contrasena").asText());
    }
}
