# Brilliant Server

Servidor independiente de cuentas para Brilliant: Java 21, Spring Boot, Spring Security y SQLite. La app integra registro, inicio de sesión, cierre de sesión y borrado. Registro requiere 3–128 caracteres y al menos puntuación o símbolo; las cuentas anteriores conservan acceso. Las salas y partidas en línea siguen pendientes.

## Arranque en la red local

En esta Mac, usar el Java 21 incluido en Android Studio. Java 24/25 falló al aceptar conexiones LAN incluso con un socket mínimo; con Java 21 el Samsung recibió HTTP 200 del servidor completo por Wi-Fi directo.

```sh
cd /Users/alfredojimenez/developer/brilliant_game/brilliant_server
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" -jar target/brilliant-server-accounts.jar --spring.profiles.active=lan
```

El perfil `lan` escucha en `0.0.0.0:8080`. En cada arranque aparece un bloque **CONEXIÓN A BRILLIANT** con la IP actual de cada interfaz de red, el puerto real y la dirección completa para escribir en **Servidor** dentro de la app. También indica `10.0.2.2` para el emulador Android en esta computadora. La IP se consulta de nuevo al arrancar; no está fijada en el código. Si hay varias interfaces, usar la dirección de la red compartida con el teléfono. Todos los dispositivos deben estar en la misma red Wi-Fi. En Brilliant Debug, abrir **Servidor**, escribir la dirección mostrada y pulsar **Guardar y conectar** una sola vez por dispositivo. Si cambia la IP, actualizarla allí. En el emulador Android de la misma Mac usar `10.0.2.2:8080`.

El JAR `target/brilliant-server-accounts.jar` es una copia local verificada, generada por separado para evitar sobrescribir el JAR de un proceso activo. Se mantienen la base y la clave en `./data`; arrancar siempre desde esta carpeta para conservar las cuentas.

## Compilación

Detener el proceso antes de reconstruir el JAR que esté usando, o compilar en otra carpeta:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./mvnw verify
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" -jar target/brilliant-server.jar --spring.profiles.active=lan
```

Sin el perfil `lan`, el servidor conserva la dirección predeterminada `127.0.0.1:8080`; también muestra la IP actual, pero avisa que solo acepta conexiones locales y que debe activarse `lan` para conectar teléfonos. Las variables disponibles están en `.env.example`; el programa no carga `.env` automáticamente. No subir datos ni secretos. Producción requiere TLS.

## Verificación de conexión y cuentas

Se comprobaron 20 pruebas Java, incluida una con cuatro clientes simultáneos que registran, inician sesión, cierran su sesión y eliminan su cuenta de forma independiente. También pasó el recorrido Flutter → JAR con configuración de dirección, persistencia de esa dirección al reiniciar el cliente, registro, login, logout y borrado. La comprobación física LAN se realizó desde un Samsung SM-N985F al servidor con Java 21. No sustituye una prueba con cuatro teléfonos ni implementa partidas en línea.

Flutter conserva la sesión únicamente en memoria y guarda solo la dirección del servidor. Cada cuenta admite una sola sesión activa. Logout la libera inmediatamente. Borrado exige contraseña actual y confirmación. El resto de capacidades existentes de la API permanece disponible, sin ampliación de alcance móvil.

La documentación de trabajo se conserva en `docs/`, excluida del repositorio. Los avisos de licencias están en [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt).

## Aviso de IP actual: 5 de noviembre de 2026

El bloque de conexión se muestra también sin perfil LAN, distingue IP de red y dirección del emulador e indica el puerto efectivo, incluido un puerto personalizado. Se comprobaron 20 pruebas Java y dos arranques reales del JAR con puerto libre: perfil LAN y acceso exclusivamente local, ambos con salud HTTP 200. Los arranques utilizaron bases y claves temporales. El artefacto actualizado se deja en `target/brilliant-server-accounts.jar` sin sobrescribir los JAR anteriores.

## Sesión exclusiva y cuentas de prueba: 5 de noviembre de 2026

Un segundo inicio con la misma cuenta devuelve **409 SESSION_ALREADY_ACTIVE**, conserva la sesión del primer dispositivo y no emite nuevos tokens. Contraseñas incorrectas siguen devolviendo el error genérico de credenciales. Se comprobó también la carrera de dos inicios simultáneos: solo uno obtiene acceso. Cada dispositivo que juegue a la vez debe usar una cuenta distinta.

La app actualizada consulta `GET /api/v1/users/me` cada 10 segundos y al volver al primer plano. Esta petición comprueba la sesión y actualiza su actividad. Tras **45 segundos sin actividad**, la sesión deja de autorizar peticiones y un nuevo acceso puede ocupar la cuenta. Esto permite recuperarla cuando se cierra la app sin hacer logout o se pierde conexión; el cliente anterior no puede recuperar su sesión al volver. Cerrar sesión la libera inmediatamente. La caducidad del token también libera el acceso, sin espera adicional. Los 401 desmontan las pantallas protegidas del cliente; un fallo de red por sí solo no anuncia cierre confirmado.

La migración V002 actualiza las bases existentes conservando las cuentas y contraseñas protegidas mediante hash. Si había varias sesiones abiertas, conserva el primer registro y revoca los demás; su actividad y caducidad siguen comprobándose. Un índice único y la transacción de escritura impiden crear sesiones simultáneas para el mismo usuario. Actualizar también la app: las versiones antiguas no envían la actividad periódica. No ejecutar un JAR anterior contra una base ya migrada a V002.

Con el perfil `lan` se crea **`data/cuentas-prueba.json`**, exclusivamente para las cuentas de prueba solicitadas. Contiene usuario y contraseña en texto legible, por UUID; SQLite sigue usando Argon2id y este archivo no participa en la autenticación. Se completa solo después de registros, accesos o renombrados correctos, conserva las contraseñas exactamente y elimina la entrada al borrar la cuenta. No permite recuperar contraseñas antiguas desde sus hashes: para añadir una cuenta existente, iniciar sesión correctamente después de liberar su sesión anterior. Un fallo de escritura del archivo no convierte una operación de cuenta ya confirmada en un fallo de autenticación.

`data/` y el nombre `cuentas-prueba.json` están excluidos explícitamente de Git; el archivo tiene permisos solo para su propietario y se reemplaza de forma atómica. No se imprimen contraseñas en la consola ni se añaden a respuestas HTTP. Fuera de `lan` está desactivado de forma predeterminada. `RECORD_TEST_ACCOUNTS=false` lo desactiva; `true` lo activa en desarrollo. Con `PRODUCTION=true`, activar este archivo impide el arranque.

Verificación: **29 pruebas Java**, **352 pruebas Flutter**, análisis sin avisos, APK Android Debug generado y recorrido real Flutter → JAR correcto. Incluye exclusión concurrente, actividad/caducidad, rollback, actualización de una base V001 y reapertura V002, archivo de pruebas, permisos, Unicode, eliminación y escrituras concurrentes. Las pruebas usan bases, claves y credenciales temporales.
