# Brilliant Server

Servidor independiente de cuentas para Brilliant, en Java 21+, Spring Boot, Spring Security y SQLite.

**Estado:** implementación en desarrollo. Esta subida conserva el trabajo actual; no acredita el cierre del plan, la validación completa de seguridad ni la preparación para producción. La última ejecución de pruebas detectó dos fallos en la clasificación de conflictos de nombre; hay una corrección posterior todavía sin verificar.

## Desarrollo

```sh
./mvnw verify
java -jar target/brilliant-server.jar
```

El primer comando requiere JDK 21 o superior y acceso a Maven Central. El JAR usa por defecto `127.0.0.1:8080` y crea datos y clave de firma en `./data`. No subir ese directorio ni secretos al repositorio. Las variables disponibles están en `.env.example`; el programa no carga `.env` automáticamente. Producción exige configurar TLS.

La documentación de trabajo se conserva localmente en `docs/`, excluida del repositorio. Los avisos de licencias están en [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt).

El proyecto Flutter se mantiene en su propio repositorio. La integración móvil y el multijugador no forman parte de esta subida.
