# Brilliant Server

Servidor independiente de cuentas para Brilliant, en Java 21+, Spring Boot, Spring Security y SQLite.

**Estado:** la API implementa registro, login, renombrado, logout y eliminación de cuenta. Flutter integra únicamente **registro, inicio de sesión y borrado**, conforme al alcance actual del usuario; el resto de endpoints se conserva sin cambios. La comprobación Maven de esta entrega completó 17 pruebas sin fallos ni errores y generó el JAR.

El cliente usa sesión solo en memoria, nombre y UUID de la cuenta en partidas locales, y borrado con contraseña actual y confirmación. La integración real Flutter → JAR se ejecutó correctamente desde el repositorio Flutter con una base y clave temporales: registro y login por interfaz, reinicio del cliente y del servidor, partida local y rechazo de login/token tras borrar. La suite normal de Flutter completó 296 pruebas; Android e iOS para simulador compilaron. Esta evidencia no acredita preparación para producción ni pruebas de teléfono físico o LAN.

## Desarrollo

```sh
./mvnw verify
java -jar target/brilliant-server.jar
```

El primer comando requiere JDK 21 o superior y acceso a Maven Central. El JAR usa por defecto `127.0.0.1:8080` y crea datos y clave de firma en `./data`. No subir ese directorio ni secretos al repositorio. Las variables disponibles están en `.env.example`; el programa no carga `.env` automáticamente. Producción exige configurar TLS.

La documentación de trabajo se conserva localmente en `docs/`, excluida del repositorio. Los avisos de licencias están en [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt).

El proyecto Flutter se mantiene en su propio repositorio. La fase 1 móvil acordada comprende crear cuenta, iniciar sesión y eliminar cuenta. Renombrado y logout permanecen como capacidades de la API, fuera de esta entrega Flutter. Las salas y partidas en línea corresponden a una fase posterior.
