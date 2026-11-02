package com.brilliant.server.config;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Settings.Values.class)
public class Settings {
    @ConfigurationProperties("app")
    public record Values(String dataDir, String databaseUrl, String issuer, String audience,
                         String publicOrigin, boolean production, long accessSeconds, long sessionSeconds,
                         String allowedOrigins, int loginIpLimit, int loginNameLimit, int registerIpLimit,
                         int refreshLimit, int accountLimit) {
        public Values {
            if (dataDir == null || dataDir.isBlank() || issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                    || accessSeconds < 1 || accessSeconds > 600 || sessionSeconds < accessSeconds || sessionSeconds > 2592000
                    || loginIpLimit < 1 || loginNameLimit < 1 || registerIpLimit < 1 || refreshLimit < 1 || accountLimit < 1)
                throw new IllegalArgumentException("Configuración app inválida");
            URI origin = URI.create(publicOrigin);
            if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                    || !(origin.getScheme().equals("https") || (!production && origin.getScheme().equals("http"))))
                throw new IllegalArgumentException("PUBLIC_ORIGIN requiere un origen HTTP(S); producción exige HTTPS");
        }
        public Path directory() {
            Path path = Path.of(dataDir).toAbsolutePath().normalize();
            try { Files.createDirectories(path); restrict(path, true); }
            catch (IOException e) { throw new IllegalStateException("No se puede preparar DATA_DIR", e); }
            return path;
        }
    }
    public static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
}
