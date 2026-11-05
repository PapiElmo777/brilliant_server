# Brilliant Server

Servidor independiente de cuentas para Brilliant: Java 21, Spring Boot, Spring Security y SQLite. La app integra registro, inicio de sesión, cierre de sesión y borrado. Registro requiere 3–128 caracteres y al menos puntuación o símbolo; las cuentas anteriores conservan acceso. Las salas y partidas en línea siguen pendientes.

## Arranque en la red local

En esta Mac, usar el Java 21 incluido en Android Studio. Java 24/25 falló al aceptar conexiones LAN incluso con un socket mínimo; con Java 21 el Samsung recibió HTTP 200 del servidor completo por Wi-Fi directo.

```sh
cd /Users/alfredojimenez/developer/brilliant_game/brilliant_server
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" -jar target/brilliant-server-ip.jar --spring.profiles.active=lan
```

El perfil `lan` escucha en `0.0.0.0:8080`. En cada arranque aparece un bloque **CONEXIÓN A BRILLIANT** con la IP actual de cada interfaz de red, el puerto real y la dirección completa para escribir en **Servidor** dentro de la app. También indica `10.0.2.2` para el emulador Android en esta computadora. La IP se consulta de nuevo al arrancar; no está fijada en el código. Si hay varias interfaces, usar la dirección de la red compartida con el teléfono. Todos los dispositivos deben estar en la misma red Wi-Fi. En Brilliant Debug, abrir **Servidor**, escribir la dirección mostrada y pulsar **Guardar y conectar** una sola vez por dispositivo. Si cambia la IP, actualizarla allí. En el emulador Android de la misma Mac usar `10.0.2.2:8080`.

El JAR `target/brilliant-server-ip.jar` es una copia local verificada, generada por separado para evitar sobrescribir el JAR de un proceso activo. Se mantienen la base y la clave en `./data`; arrancar siempre desde esta carpeta para conservar las cuentas.

## Compilación

Detener el proceso antes de reconstruir el JAR que esté usando, o compilar en otra carpeta:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./mvnw verify
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" -jar target/brilliant-server.jar --spring.profiles.active=lan
```

Sin el perfil `lan`, el servidor conserva la dirección predeterminada `127.0.0.1:8080`; también muestra la IP actual, pero avisa que solo acepta conexiones locales y que debe activarse `lan` para conectar teléfonos. Las variables disponibles están en `.env.example`; el programa no carga `.env` automáticamente. No subir datos ni secretos. Producción requiere TLS.

## Verificación de conexión y cuentas

Se comprobaron 20 pruebas Java, incluida una con cuatro clientes simultáneos que registran, inician sesión, cierran su sesión y eliminan su cuenta de forma independiente. También pasó el recorrido Flutter → JAR con configuración de dirección, persistencia de esa dirección al reiniciar el cliente, registro, login, logout y borrado. La comprobación física LAN se realizó desde un Samsung SM-N985F al servidor con Java 21. No sustituye una prueba con cuatro teléfonos ni implementa partidas en línea.

Flutter conserva la sesión únicamente en memoria y guarda solo la dirección del servidor. Logout revoca la sesión actual y conserva otras sesiones. Borrado exige contraseña actual y confirmación. El resto de capacidades existentes de la API permanece disponible, sin ampliación de alcance móvil.

La documentación de trabajo se conserva en `docs/`, excluida del repositorio. Los avisos de licencias están en [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt).

## Aviso de IP actual: 5 de noviembre de 2026

El bloque de conexión se muestra también sin perfil LAN, distingue IP de red y dirección del emulador e indica el puerto efectivo, incluido un puerto personalizado. Se comprobaron 20 pruebas Java y dos arranques reales del JAR con puerto libre: perfil LAN y acceso exclusivamente local, ambos con salud HTTP 200. Los arranques utilizaron bases y claves temporales. El artefacto actualizado se deja en `target/brilliant-server-ip.jar` sin sobrescribir los JAR anteriores.
