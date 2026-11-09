# Brilliant Server

Servidor independiente de cuentas para Brilliant: Java 21, Spring Boot, Spring Security y SQLite. La app integra registro, inicio de sesión, cierre de sesión y borrado. Registro requiere 3–128 caracteres y al menos puntuación o símbolo; las cuentas anteriores conservan acceso. La rama `multiplayer` ya incluye salas y lobby persistentes; incluye también canal de eventos y chat; preparación, rondas de 60 segundos, premios globales, votación y resultado están implementados; animaciones finales y revancha siguen pendientes.

## Arranque en la red local

En esta Mac, usar el Java 21 incluido en Android Studio. Java 24/25 falló al aceptar conexiones LAN incluso con un socket mínimo; con Java 21 el Samsung recibió HTTP 200 del servidor completo por Wi-Fi directo.

```sh
cd /Users/alfredojimenez/developer/brilliant_game/brilliant_server
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" -jar target/brilliant-server.jar --spring.profiles.active=lan
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

## Comprobación en Samsung y emulador: 5 de noviembre de 2026

Se diagnosticó que seguía abierto `target/brilliant-server-lan.jar` anterior a la exclusión de sesiones. Se respaldó SQLite en `backups/` y se actualizaron, con el servidor detenido, los artefactos locales `brilliant-server.jar`, `brilliant-server-lan.jar` y `brilliant-server-ip.jar` a la compilación verificada de cuentas. Para los próximos arranques se recomienda el nombre estándar **`target/brilliant-server.jar`**, que Maven genera y actualiza al compilar, evitando elegir un artefacto histórico. Los JAR siguen excluidos de Git.

El servidor actualizado arrancó sobre la base existente con migración V002. Se probó con una cuenta temporal usando peticiones desde el Samsung SM-N985F por Wi-Fi y el emulador Android: registro Samsung 201, login emulador 409 SESSION_ALREADY_ACTIVE, sesión original Samsung 200, logout Samsung 204, login emulador 200 y token anterior Samsung 401. La cuenta temporal se eliminó con 204. Se comprobó también que ambas apps tenían guardada la dirección del mismo servidor. Esta prueba comprueba el acceso HTTP real desde ambos dispositivos; no representa una partida multijugador sincronizada.

## Plan de partidas multijugador: 5 de noviembre de 2026

Se redactó el plan local `docs/PLAN_IMPLEMENTACION_MULTIJUGADOR.md`, sincronizado entre ambos proyectos y excluido de Git junto con la documentación de trabajo. Incluye salas de **2–6 jugadores en total**, menú Un jugador/Multijugador, lobby con Listo e Iniciar, preparación individual, dados comunes, rondas de 60 segundos, chat, reclamos compartidos, ranking, cambio de anfitrión, animación de derrota y revancha. Metas confirmadas: 2 → 26, 3 → 24, 4 → 22, 5–6 → 20. Los puestos de zona 4–6 dan 0 puntos; el ranking desempata por zonas completadas y después por quién alcanzó antes su puntuación. El número del lobby es la capacidad máxima y se puede iniciar desde dos presentes listos.

El documento separa requisitos confirmados, propuestas técnicas, casos por decidir, siete entregas y su matriz de verificación. Esta actualización es solo planificación: las partidas sincronizadas aún no están implementadas ni probadas; las cifras de pruebas anteriores corresponden al juego local y a cuentas/conexión.

Reglas posteriores confirmadas para este plan: conservar la meta pese a abandonos; el último participante gana por abandono. Se reemplaza el cierre automático por bloqueo por una **votación tras tres rondas consecutivas sin movimientos válidos para nadie**, aprobada por **más de la mitad de quienes siguen participando**. Una omisión voluntaria o timeout con movimientos disponibles no cuenta como bloqueo. El plan local incluye los estados, mayorías de 2–6 y pruebas previstas; la votación tiene un máximo de **60 segundos** y, si se aprueba, el **primero del ranking gana y recibe la animación de victoria**. Los demás ven su derrota y puesto.

**Flujo de implementación acordado:** al comenzar el desarrollo se creará la rama **`multiplayer`** desde `main` en cada repositorio (Flutter y servidor). Toda la implementación relacionada con el multijugador se hará allí, con commits por entrega. Cuando la funcionalidad completa esté terminada y verificada, se hará merge de `multiplayer` a **`main`** en ambos proyectos. Esta actualización solo documenta el acuerdo; las ramas se crearán al iniciar la implementación.


## Inicio de implementación multijugador: 5 de noviembre de 2026

Trabajo iniciado en la rama **`multiplayer`** de ambos repositorios, siguiendo el plan local. Primer bloque de la entrega 1: meta de seis jugadores (20), ranking por puntos/zonas/orden de puntuación, catálogo JSON de los cuatro mapas y motor Java de preparación, restricciones y anclas. El [contrato v1](contracts/multiplayer-v1.md) y sus [esquemas JSON](contracts/multiplayer-v1.schema.json) quedan versionados junto al código. Sus rutas de salas/partidas todavía no están expuestas.

Flutter conserva access y refresh únicamente en memoria y renueva el acceso antes de caducar, manteniendo la sesión exclusiva. Coordina renovación con logout/borrado, ignora respuestas tardías y respeta Retry-After. Ante respuesta de red incierta de refresh no reenvía el secreto; el acceso actual termina al caducar o recibir 401. Un proceso nuevo sigue requiriendo login.

Paridad comprobada mediante **80 estados y 2880 combinaciones de dados** compartidos por Dart y Java, con copias idénticas del catálogo y corpus. Verificación final: **362 pruebas Flutter aprobadas** (recorrido JAR optativo omitido) y análisis sin avisos. La suite Java tiene **33 pruebas aprobadas**, incluida actividad durante 21 minutos con reloj controlado, dos rotaciones y la misma sesión. La verificación de sesión exclusiva no acredita pertenencia única a salas: esta comprobación sigue pendiente al integrar su persistencia.

La entrega 1 permanece en curso. Aún faltan salas/lobby, canal/chat, rondas sincronizadas, premios transaccionales, continuidad, final y revancha. No se ha hecho merge a `main`, modificado las cuentas existentes ni reiniciado el servidor LAN. Los empates absolutos y las demás decisiones pendientes del plan continúan sin resolverse.


## Menú y lobby multijugador: 5 de noviembre de 2026

Implementada la entrega 2 en **`multiplayer`**: menú Un jugador/Multijugador, listado, Alojar partida con nombre/capacidad, cuatro mapas y selección aleatoria, participantes, Listo e Iniciar. Solo el anfitrión cambia configuración. Por indicación del usuario, **Listo se conserva al cambiar mapa o capacidad**; el invitado puede quitarlo y no se admite una capacidad menor que los presentes.

SQLite V003 conserva V001/V002 y agrega pertenencia única por cuenta, recibos idempotentes y eventos. Entradas simultáneas no sobrepasan el cupo; inicio/entrada congelan una lista válida. El cliente recupera la sala existente, consulta cada tres segundos en primer plano y confirma la salida con Java. Reintentos de resultados inciertos conservan el mismo comando. Logout/borrado liberan lugar y transfieren anfitrión sin eliminar el historial de los demás. Las reglas de abandono durante las rondas siguen en entrega 5.

**Alcance del inicio:** fija mapa, participantes, meta e ID de partida en PREPARING. La preparación individual y las rondas compartidas todavía no están integradas; no se abre una partida local en su lugar. WebSocket y chat corresponden a la siguiente entrega. Las cuentas reales y el servidor LAN no se han modificado ni reiniciado.

Verificación: **376 pruebas Flutter aprobadas**, dos recorridos JAR optativos omitidos en la suite normal, análisis sin avisos, **43 pruebas Java aprobadas**, reapertura V003 e identidades históricas. Se ejecutó aparte el [recorrido real de salas](contracts/multiplayer-v1.md): siete clientes Dart independientes contra el JAR, seis en una sala, séptimo rechazado y otra sala aislada, preparación común y transferencia de anfitrión. No es una prueba de seis teléfonos ni de dados compartidos. Capturas de menú/lobby revisadas; pruebas de 320×568, 390×844, horizontal y texto 2× sin desbordamientos.

El JAR estándar actualizado y el APK Android Debug están generados. Antes de arrancarlo sobre una base existente, respaldar SQLite con el proceso detenido: V003 se aplicará al arrancar y no se debe volver a un JAR que solo conozca V001/V002. Las pruebas usaron carpetas y cuentas temporales. El merge a `main` se mantiene reservado para completar y verificar todo el multijugador.


## Canal de eventos y chat: 6 de noviembre de 2026

Implementada la entrega 3 en **`multiplayer`**. El lobby recibe instantánea y eventos ordenados por WebSocket, comprueba saltos/duplicados y reconecta conservando identidad, mapa y Listo. La renovación de token sustituye el canal; aperturas simultáneas dejan vigente el último vínculo confirmado. Bearer solo en cabecera, sin query ni redirecciones, con autorización y caducidad comprobadas durante toda la conexión.

Chat disponible en lobby y preparación: burbuja con no leídos junto a los controles, mensajes de sistema y jugadores diferenciados, historial paginado y borrador conservado ante respuestas inciertas. Texto plano de hasta 300 puntos de código, 10 mensajes por cuenta cada 10 segundos, reintentos idempotentes y Retry-After. El servidor conserva historial; el cliente carga páginas de 20 y hasta 200 mensajes en memoria. Se mantienen los botones uniformes del menú principal.

V004 incorpora presencia de canal y mensajes sin cambiar migraciones aplicadas. Una vez enlazado, solo sus latidos mantienen la presencia de sala; consultar la cuenta o GET no oculta un canal perdido. Se reserva el lugar hasta 45 segundos y se recupera el mismo miembro dentro del plazo. El canal lee eventos después del commit, usa envíos acotados y evita que un cliente lento bloquee a otros. Las reglas de abandono y victoria durante rondas pertenecen a entrega 5.

Verificación: **394 pruebas Flutter aprobadas**, análisis sin avisos, **51 pruebas Java aprobadas** y recorrido real adicional contra el JAR con **seis canales Dart y otra sala aislada**: chat común, reconexión, renovación, mapa/Listo, latidos y preparación. Pruebas de teclado, texto 2×, envío incierto y logout con chat abierto. Capturas revisadas y JAR/APK Debug generados. Los dos recorridos JAR optativos se omiten en la suite normal; el de salas se ejecutó por separado. No representa seis teléfonos físicos ni una partida con dados compartidos.

El [contrato actualizado](contracts/multiplayer-v1.md) detalla frames, cierre, límites e historial. La siguiente entrega integra preparación individual y rondas de 60 segundos. No se reinició el servidor LAN ni se tocaron las cuentas reales. Respaldar SQLite con el servidor detenido antes de arrancar este JAR sobre datos existentes: aplicará V004 y después no se debe arrancar un JAR que solo conozca V003. No se ha hecho merge a `main`.


## Multijugador, entrega 4 — preparación individual y rondas compartidas (6 de noviembre de 2026)

Implementadas y verificadas las entregas 1–4 en `multiplayer`. Preparación manual privada de 1–6, confirmación definitiva y espera de todos los activos. Java genera dados comunes y rondas de exactamente 60 segundos; valida una sola colocación u omisión por participante y ronda. El timeout convierte pendientes en omisión y el avance es único incluso frente a acciones concurrentes.

V005 conserva V001–V004, migra participantes PREPARING y persiste tableros, rondas, acciones y proyecciones privadas por evento. HTTP y seis canales reciben exclusivamente su propio tablero; reconectar restaura la misma ronda. Flutter usa reloj monotónico anclado al servidor, propuestas locales, confirmación, omisión y animación de los dados recibidos. Se conserva Listo al cambiar configuración y el estilo uniforme del menú.

Verificación: 412 pruebas Flutter, análisis sin avisos, 59 pruebas Java y recorrido adicional de siete clientes/seis canales contra el JAR temporal: seis preparaciones diferentes, privacidad, dados/plazo iguales, colocación, omisiones, ronda siguiente, rechazo de ronda antigua y reconexión. Java verifica además timeout a los 60 segundos, carreras y migración V004→V005. Seis capturas nuevas revisadas en tres tamaños; controles probados con texto 2×. JAR y APK Debug generados.

Todo permanece en `multiplayer`, con commits en español y sin merge a main. No se reinició el servidor LAN ni se tocaron datos reales; respaldar SQLite con el proceso detenido antes de aplicar V005. La siguiente entrega es la 5: reclamos/premios transaccionales, continuidad, victoria y votación. No se han añadido puntos globales, final, ranking ni revancha en esta fase. Se mantienen pendientes las decisiones explícitas del plan local.


## Entrega 5 — premios, continuidad y votación (8 de noviembre de 2026)

Implementadas las entregas 1–5 en `multiplayer`. Reclamos y puestos globales únicos, cuarto–sexto +0, marcador propio/rivales, aviso de sistema y actualización de premios disponibles. Colocación, puntos y eventos son transaccionales e idempotentes. Alcanzar la meta fija un único ganador y resultado inmutable; no se aceptan más acciones ni se abre otra ronda.

Confirmaciones del usuario de esta entrega: pausar rondas durante votación; si no se aprueba, continuar y esperar otras tres rondas bloqueadas; retirar el voto de quien abandona y recalcular mayoría. Ranking con abandonos después de activos y orden de ingreso para empates absolutos, tras puntos/zonas/secuencia positiva. Ganador primero. Estas reglas reemplazan las propuestas anteriores.

Tres rondas sin movimientos legales para nadie abren una votación de 60 segundos; omitir con movimientos disponibles rompe la racha. Mayoría absoluta de activos, un voto definitivo por participante, cierre anticipado si se aprueba o resulta imposible aprobar y continuación al expirar sin mayoría. Reconectar y transferir anfitrión conserva la votación. Con un restante prevalece victoria por abandono, manteniendo meta y reclamos; un lote de expiraciones simultáneas no inventa un ganador por el orden de eliminación.

V006 preserva los checksums V001–V005, tableros, rondas y acciones. Agrega progreso, reclamos, votaciones y votos. Resultado con ganador, motivo, posiciones, nombres y puntos congelados; el borrado de cuentas o salidas posteriores no lo reordenan. La UI muestra un resumen del resultado; animaciones de victoria/derrota y revancha corresponden a entrega 6. La política de reinicio de Java y el anfitrión de revancha siguen pendientes.

Verificación: 427 pruebas Flutter, análisis sin avisos y 75 pruebas Java. Incluye reclamos concurrentes de seis, premios +0, reversión completa ante fallo, resultado fijo, mayorías 2–6, voto idempotente, plazo/racha, abandonos, reconexión real y migración V005→V006. Recorrido adicional de siete clientes Dart/seis canales contra el JAR con datos temporales: colocaciones legales hasta completar zonas reales, premios comunes, tableros privados, conservación de reclamos y victoria del último participante. No es una prueba de seis teléfonos. Nueve capturas nuevas/actualizadas de marcador, votación y resultado en tres tamaños; pruebas con texto 2× y chat accesible. JAR y APK Debug generados.

Trabajo en `multiplayer`, commits en español y sin merge a main. No se reinició el servidor LAN ni se tocaron datos reales. Respaldar SQLite con el proceso detenido antes de arrancar el JAR nuevo: aplica V006 y no se debe volver a un JAR anterior. Siguiente entrega: animaciones finales, presentación de resultados y revancha (fase 6).
