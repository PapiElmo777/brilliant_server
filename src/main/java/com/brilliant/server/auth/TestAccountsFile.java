package com.brilliant.server.auth;

import com.brilliant.server.config.Settings;
import com.brilliant.server.users.Accounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Local development aid explicitly requested for test accounts; never an auth source. */
@Component
public class TestAccountsFile {
    private static final Logger LOG = LoggerFactory.getLogger(TestAccountsFile.class);
    private final ObjectMapper json;
    private final Accounts accounts;
    private final Path file;

    public TestAccountsFile(Settings.Values settings, Environment env, ObjectMapper json, Accounts accounts) throws IOException {
        this.json=json;
        this.accounts=accounts;
        boolean enabled=env.getProperty("app.record-test-accounts",Boolean.class,false);
        if(enabled && settings.production()) throw new IllegalStateException("El archivo de cuentas de prueba no se permite en producción");
        file=enabled ? settings.directory().resolve("cuentas-prueba.json") : null;
        if(file!=null) {
            if(Files.isSymbolicLink(file)) throw new IOException("El archivo de pruebas no puede ser un enlace simbólico");
            if(!Files.exists(file)) save(json.createObjectNode());
            Settings.restrict(file,false);
            LOG.info("Archivo local de cuentas de prueba: {}",file);
        }
    }

    public synchronized void remember(String id,String password) {
        if(file==null) return;
        try {
            ObjectNode entries=read();
            var current=accounts.byId(id);
            if(current==null) entries.remove(id);
            else entries.set(id,json.createObjectNode().put("usuario",current.user().username()).put("contrasena",password));
            save(entries);
        } catch(IOException | org.springframework.dao.DataAccessException e) {
            LOG.warn("No se pudo actualizar el archivo local de cuentas de prueba; la operación de cuenta sí se confirmó.");
        }
    }

    public synchronized void forget(String id) {
        if(file==null) return;
        try {
            ObjectNode entries=read();
            entries.remove(id);
            save(entries);
        } catch(IOException e) {
            LOG.warn("No se pudo quitar la cuenta del archivo local de pruebas; el borrado en el servidor sí se confirmó.");
        }
    }

    private ObjectNode read() throws IOException {
        if(Files.isSymbolicLink(file)) throw new IOException("Archivo de pruebas inválido");
        var node=json.readTree(file.toFile());
        if(!(node instanceof ObjectNode entries)) throw new IOException("Archivo de pruebas inválido");
        return entries;
    }

    private void save(ObjectNode entries) throws IOException {
        Path temporary=Files.createTempFile(file.getParent(),".cuentas-prueba-",".tmp");
        try {
            Settings.restrict(temporary,false);
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),entries);
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
