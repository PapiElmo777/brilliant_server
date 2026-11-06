# Multijugador — contrato v1

Implementación de las entregas 1–4 del plan, actualizada el 6 de noviembre de 2026. Las rutas HTTP de salas, ajustes, Listo y comienzo de preparación están expuestas. WebSocket, envío de chat e historial están disponibles. Preparación y acciones de ronda están disponibles; votación, premios, final y revancha siguen pendientes. Cuentas conserva sus rutas existentes.

Los esquemas JSON Schema 2020-12 de cuerpos, respuestas y sobres de evento están en `multiplayer-v1.schema.json` (`$defs`). Los esquemas de sala, miembro, listado e instantánea describen la entrega actual; los sobres de eventos y el historial describen el canal actual. No sustituyen autorización ni validación de dominio. El esquema de match incluye preparación privada y rondas; los resultados se ampliarán en entregas posteriores.

## Transporte y autoridad

HTTP `/api/v1`, Bearer en cabecera y WebSocket `/api/v1/rooms/{roomId}/events` en el mismo origen. TLS fuera de Debug. No enviar tokens en URL. La identidad procede de la sesión autenticada; ningún comando acepta identidad, puntos, premios, anfitrión o dados enviados por el cliente como autoridad.

Toda mutación de multijugador lleva `commandId` UUID en el cuerpo. El servidor guarda `(userId, commandId, operación, recurso, contenido canónico, respuesta)` en la misma transacción del efecto. Repetirlo devuelve la respuesta original; cambiar operación/recurso/contenido produce 409 COMMAND_CONFLICT. La unicidad de miembro usa cuenta, no token, por lo que refresh no añade participantes. Solo una pertenencia activa por cuenta. Implementado en SQLite V003 con índice único y transacciones BEGIN IMMEDIATE. Los recibos se guardan para comandos aceptados; una petición rechazada no produce efectos ni un recibo de éxito.

## Estados

Sala: LOBBY, PREPARING, PLAYING, FINISHED, CLOSED.
Partida: PREPARING, ROUND_ACTIVE, END_VOTE, FINISHED, CANCELLED.
Acción: PLACE o SKIP, una por participante/ronda. Con dados `[a,b]`, `dieIndex` 0 coloca a y usa b como ancla; 1 coloca b y usa a. Ninguna colocación confirmada puede deshacerse.

`rulesVersion: 1` referencia `catalog-v1.json`: cuatro mapas, coordenadas base cero, casillas `f1_c1`, reglas RED/YELLOW distintas, BLUE/WHITE iguales, GREEN libre, PURPLE hasta dos distintos, ORANGE_LOW 1–3 y ORANGE_HIGH 4–6. Los premios son por zona/mapa; puestos 4–6 dan cero. Todas las colocaciones tienen rango 1–6, adyacencia ortogonal a un número igual al otro dado y destino vacío. Preparación requiere exactamente las seis casillas iniciales con 1–6 sin repetidos y reglas válidas.

Meta con participantes iniciales: 2→26, 3→24, 4→22, 5–6→20. Permanece fija ante abandonos. Ranking: puntos descendentes, zonas descendentes, secuencia del último premio positivo ascendente. Completar con cero no cambia el momento de alcanzar la puntuación. El motor conserva posición compartida en empates absolutos: falta confirmar su resolución antes del resultado final/votación. No se decide silenciosamente el orden de abandonos ni las políticas pendientes de revancha/votación/reinicio.

## Operaciones y cuerpos

Todas las respuestas de comando: `{commandId, roomId, sequence, data}`. `data` es la instantánea autorizada resultante; reintentar devuelve la misma respuesta aunque haya eventos posteriores. GET no requiere commandId.

| Método / ruta | Campos adicionales del cuerpo | Entrega |
|---|---|---|
| GET /rooms | —; `{rooms:[{id,name,hostName,capacity,occupancy,levelId,state}],currentRoomId}` | 2 |
| POST /rooms | name (1–80 caracteres tras trim), capacity (2–6) | 2 |
| POST /rooms/{id}/join | — | 2 |
| POST /rooms/{id}/leave | — | 2 |
| GET /rooms/{id} | — | 2 |
| PATCH /rooms/{id}/settings | expectedRevision, capacity, levelId (tablero_01…04 o null si todavía no se eligió mapa) | 2 |
| POST /rooms/{id}/random-level | expectedRevision | 2 |
| PUT /rooms/{id}/ready | ready (boolean), expectedRevision | 2 |
| POST /rooms/{id}/start | expectedRevision | 2 |
| GET /rooms/{id}/messages | before opcional (secuencia exclusiva); página de hasta 20, nextBefore nullable | 3 |
| WebSocket /rooms/{id}/events | Bearer en cabecera; sin parámetros de consulta | 3 |
| POST /rooms/{id}/messages | text (1–300 caracteres de texto plano) | 3 |
| PUT /matches/{id}/preparation | placements (objeto celda→entero) | 4 |
| POST /matches/{id}/rounds/{roundId}/actions | kind (PLACE/SKIP); PLACE exige dieIndex (0/1), cellId; SKIP los omite | 4 |
| GET /matches/{id}/end-vote | — | 5 |
| PUT /matches/{id}/end-vote/ballot | voteId, approve (boolean) | 5 |
| POST /matches/{id}/rematch | — | 6 |

Configuración lleva revisión para detectar cambios antiguos; la revisión global de eventos no invalida jugadas de rivales. PATCH reemplaza capacidad y nivel juntos; nivel aleatorio es una decisión del servidor y su reintento no vuelve a sortear. El anfitrión inicia desde dos presentes, mapa elegido y todos los invitados listos; su botón expresa su disponibilidad. Regla confirmada por el usuario: conservar Listo cuando cambie mapa o capacidad. El invitado puede quitar Listo. Se rechaza una capacidad menor que la ocupación actual.

## Instantáneas y eventos

Instantánea: `{protocolVersion:1, rulesVersion:1, roomId, sequence, serverTime, room, match, messages}`. `room`: id, name, hostMemberId, capacity, levelId nullable, revision, state, members (memberId, userId, username, joinOrder, ready, connected). `match` es null en lobby; después contiene id, state, levelId, target, participants, ownBoard, round y result. Solo se envía el tablero del solicitante. `round`: id, dice de dos enteros, deadline (UTC ISO-8601), resolvedMemberIds. El resultado final será inmutable.

Evento: `{protocolVersion:1, roomId, sequence, type, serverTime, data}`. Secuencia entera estrictamente creciente por sala, persistida con los efectos. Enviar después del commit. La primera respuesta del canal es instantánea; evento con secuencia ≤ última se ignora; un salto solicita GET de instantánea y bloquea comandos hasta reconciliar. Renovación sustituye el canal con nuevo Bearer manteniendo cuenta/miembro; una conexión nueva invalida la anterior. Autorización, buffers, latidos y plazo de 45 segundos implementados en entrega 3; véase el protocolo abajo.

Tipos actuales y reservados: SNAPSHOT, MEMBER_CONNECTION_CHANGED, MEMBER_JOINED, MEMBER_LEFT, HOST_CHANGED, SETTINGS_CHANGED, READY_CHANGED, MATCH_STARTED, PREPARATION_CONFIRMED, ROUND_STARTED, ACTION_RESOLVED, ZONE_CLAIMED, MESSAGE_CREATED, END_VOTE_OPENED, END_VOTE_UPDATED, END_VOTE_RESOLVED, MATCH_FINISHED. Cada evento ofrece la instantánea autorizada en `data`; MESSAGE_CREATED incluye la instantánea con el mensaje en messages: `{id,sequence,kind:PLAYER|SYSTEM,authorMemberId,authorName,text,serverTime}`. Los mensajes SYSTEM se generan exclusivamente en Java.

## Errores

Formato existente `{error:{code,...}}`; el cliente decide por code, nunca por texto.

- 400 VALIDATION_ERROR, INVALID_PREPARATION, INVALID_MOVE, INVALID_DICE.
- 401 UNAUTHORIZED, ACCESS_TOKEN_EXPIRED, SESSION_INVALID.
- 403 NOT_ROOM_MEMBER, NOT_MATCH_PARTICIPANT, HOST_ONLY.
- 404 ROOM_NOT_FOUND, MATCH_NOT_FOUND.
- 409 PREPARATION_CLOSED, PREPARATION_ALREADY_CONFIRMED, CAPACITY_BELOW_OCCUPANCY, LEVEL_REQUIRED, HOST_STARTS_MATCH, ROOM_FULL, ROOM_ALREADY_STARTED, ALREADY_IN_ROOM, STALE_SETTINGS, COMMAND_CONFLICT, NOT_ENOUGH_PLAYERS, PLAYERS_NOT_READY, PREPARATION_PENDING, ROUND_EXPIRED, ACTION_ALREADY_RESOLVED, MATCH_FINISHED, VOTE_NOT_ACTIVE.
- 429 RATE_LIMITED con Retry-After.

## Renovación implementada

`POST /auth/refresh` con `{refreshToken}` devuelve el mismo formato de login y rota ambos tokens sin crear otra sesión. Flutter conserva secretos solo en memoria y renueva 60 segundos antes del vencimiento, con una petición en curso. Logout/borrado esperan esa petición. 401/403 invalidan rutas; una respuesta tardía no restaura identidad. 429 respeta Retry-After. Un fallo de red/5xx deja incierto si el secreto se consumió: no se reenvía y se conserva acceso hasta su caducidad o un 401. Al reiniciar proceso se requiere login.

## Verificación y reproducción

Cliente: `flutter test`, `flutter analyze`, `flutter test tool/export_multiplayer_catalog_test.dart`.
Servidor: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./mvnw test`.

Regenerar intencionalmente con `flutter test tool/export_multiplayer_catalog_test.dart --dart-define=UPDATE_MULTIPLAYER_CATALOG=true` y `flutter test test/multiplayer/rules_parity_test.dart --dart-define=UPDATE_MULTIPLAYER_CATALOG=true`. Copiar catálogo a `src/main/resources/multiplayer/` y corpus a `src/test/resources/multiplayer/` del servidor. Comparar copias byte a byte antes del commit. No modificar fixtures para encubrir diferencias del motor.


## Alcance verificado de lobby

Todas las rutas de sala responden 200 en éxito. GET /rooms enumera los lobbies, también los llenos, y devuelve currentRoomId para recuperar una pertenencia existente sin volver a unirse. GET /rooms/{id} solo es accesible para miembros activos de esa sala. Flutter consulta el listado cada tres segundos en primer plano. Dentro de una sala recibe eventos por WebSocket; deja de consultar GET periódicamente. En segundo plano cierra el canal y al volver recupera la instantánea.

Una vez enlazado el canal, la presencia de sala caduca tras 45 segundos sin latido de ese canal, por separado del heartbeat de cuenta. Consultar GET o cambiar Listo no prolonga una conexión perdida. Antes del primer canal se mantiene temporalmente la presencia HTTP de entrega 2. La renovación conserva el sid y el miembro. Logout/borrado retiran al miembro en la misma transacción de cuenta. La salida cambia anfitrión al conectado de mayor antigüedad y cierra una sala vacía. Los nombres e identidades de participación se conservan para el historial aunque se borre la cuenta. Estas comprobaciones cubren lobby/entrada a preparación; las reglas de victoria por abandono durante la partida corresponden a la entrega 5.

POST /start crea una partida PREPARING, congela nivel, participantes y meta; no crea dados ni inicia el modo local. La interfaz muestra este estado sin permitir cambios de configuración. La preparación individual y las rondas están implementadas en entrega 4. En la entrega actual no se implementa una política de recuperación o cancelación de partidas en juego tras reiniciar Java.

Recorrido real: `flutter test test/multiplayer/rooms_api_e2e_test.dart --dart-define=RUN_ROOMS_API_E2E=true`. Requiere JAR compilado y Java 21; ambos se pueden seleccionar con ROOMS_SERVER_JAR y ROOMS_SERVER_JAVA. El recorrido usa siete repositorios Dart independientes, un puerto libre y SQLite/cuentas temporales, y termina su proceso Java. Verifica lobby de seis, séptimo rechazado, aislamiento de otra sala, permisos, seis canales, chat compartido, renovación/reconexión con la misma identidad, mismo identificador de preparación y transferencia de anfitrión; también verifica seis preparaciones privadas, dados/plazo comunes, colocación y omisiones, avance único, rechazo de ronda antigua y restauración del tablero al reconectar. No acredita seis teléfonos físicos.


## Canal e historial — entrega 3

V004 añade vínculo de canal y mensajes. Mantiene los checksums V001/V002/V003. Los eventos y mensajes se insertan con los cambios y recibos de comandos; el canal lee únicamente filas confirmadas y realiza los envíos fuera de la transacción SQLite. Se envía un lote de hasta 32 eventos ordenados por conexión, con comprobación de sesión, miembro, canal vigente y vencimiento del Bearer antes de cada lote; los latidos también se autorizan. La sustitución concurrente se decide por el vínculo almacenado en SQLite, no por el orden de ejecución de callbacks.

Primer frame: sobre de evento con type SNAPSHOT y data igual a GET /rooms/{id}. Después llegan eventos con secuencia creciente. Flutter ignora secuencias ya aplicadas, evita retroceder al recibir un recibo HTTP antiguo y ante un salto consulta GET y sustituye el canal para obtener una instantánea nueva. Mientras reconcilia, bloquea cambios de configuración, Listo, inicio y nuevos mensajes. Reintentar un comando aceptado conserva su commandId.

Cliente → servidor: exclusivamente `{"type":"PING"}` cada 10 segundos. Servidor → cliente: `{"type":"PONG","serverTime":"..."}`, sin alterar la secuencia de sala. Flutter detecta 25 segundos sin respuesta y reintenta con esperas de 1, 2, 4 y hasta 8 segundos. Al cambiar Bearer cierra el canal anterior y abre otro; las generaciones descartan respuestas tardías y serializan aperturas pendientes.

Cierres: 4001 sesión/token inválido, 4003 miembro retirado o presencia agotada, 4009 conexión sustituida. Un 4009 no provoca una pelea de reconexiones: el cliente muestra el estado y espera que el usuario pulse Actualizar. Los cortes transitorios reservan el lugar hasta 45 segundos y conservan Listo, mapa y rol; al vencer se aplica la salida existente una sola vez. La política de victoria por abandono durante rondas se integrará en entrega 5.

Límites del canal: entrada de hasta 256 caracteres de texto o 256 bytes binarios, salida hasta 64 KiB, decorador serializado con buffer máximo 128 KiB. Ocho trabajadores y cola de 64 tareas separan los envíos de los hilos HTTP; Tomcat tiene límites de escritura y cierre de 1,5 segundos. Un cliente lento se cierra/reconecta. No se admiten credenciales en query ni redirecciones en el cliente Dart. Origin usa la política existente de orígenes explícitos; el cliente nativo envía Bearer en cabecera.

Chat: texto plano de 1–300 puntos de código Unicode, sin controles salvo salto de línea. Solo Java crea SYSTEM (entrada, salida, cambio de anfitrión e inicio de preparación), con los nombres de participación. El usuario solo envía text y commandId; campos adicionales como kind se rechazan. Límite de 10 mensajes por cuenta cada 10 segundos, con 429 y Retry-After; reintentar un éxito previo no consume otro envío. Los mensajes pertenecen a la sala y conservan autor/nombre histórico tras borrar la cuenta.

La instantánea contiene los últimos 20 mensajes; GET /messages?before=N obtiene los 20 anteriores en orden ascendente y devuelve nextBefore. El cliente conserva hasta 200 mensajes en memoria y detiene la carga al alcanzar ese límite. Muestra no leídos del historial cargado, conserva el borrador ante un resultado incierto y lo limpia al confirmar el mismo comando. El panel se cierra con el teclado abierto; logout/caducidad desmontan también el chat. La burbuja comparte la franja de controles del lobby sin superponerse al mapa.

El outbox conserva estado público con ownBoard vacío; V005 almacena por separado la proyección privada de cada participante en la misma transacción y secuencia. HTTP y WebSocket envían solo el tablero del solicitante. Los eventos atrasados usan su proyección histórica, sin mezclar un tablero futuro con dados anteriores. Votación, premios, finales, revancha y recuperación de partidas en juego tras reinicio permanecen pendientes.

Referencias de implementación: [WebSocket en Spring Framework 6.2](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/server.html), [WebSocket.connect de Dart](https://api.dart.dev/dart-io/WebSocket/connect.html) y [límites de Tomcat 10.1](https://tomcat.apache.org/tomcat-10.1-doc/web-socket-howto.html). No se actualizaron Spring Boot ni las versiones de reglas por esta entrega.


## Preparación y rondas — entrega 4

PUT /matches/{id}/preparation confirma exactamente seis posiciones iniciales con los números 1–6 sin repetir. Java valida casillas y restricciones del mapa congelado; la confirmación es definitiva. Se publica PREPARATION_CONFIRMED sin revelar posiciones ajenas. La primera ronda empieza únicamente cuando todos los participantes activos confirman; no se añade un plazo a la preparación.

Cada ROUND_STARTED contiene id, number, dice, startedAt, deadline, resolvedMemberIds y ownAction. Java genera los dos dados una sola vez y calcula ambos instantes desde el mismo reloj: deadline = startedAt + 60 segundos. match incluye preparedMemberIds, ownPreparationConfirmed y ownBoard (objeto celda→entero). El reloj Flutter parte de serverTime y avanza con tiempo monotónico; al llegar a cero bloquea nuevos envíos, mientras Java decide el vencimiento definitivo.

PLACE exige dieIndex 0/1 y cellId: el otro dado es el ancla. SKIP no acepta esos campos. Java comprueba pertenencia, ronda vigente, plazo, destino, adyacencia y restricciones. Una acción aceptada es inmutable. Toda propuesta visual permanece local hasta confirmar; timeout la descarta y registra SKIP con reason TIMEOUT. ownAction contiene kind, dieIndex, cellId y reason PLAYER/TIMEOUT, o null si está pendiente. Los campos dieIndex/cellId son null en SKIP.

La transacción serializada y los índices únicos resuelven la carrera entre última acción y vencimiento: una sola acción por participante/ronda y una sola ronda siguiente. Todos los activos deben resolver antes del avance anticipado. El temporizador del servidor revisa vencimientos cada 250 ms y también antes de comandos. Los recibos conservan su instantánea privada original; el orden de las propiedades de placements no cambia la identidad canónica del comando.

V005 agrega match_players, match_rounds, round_actions y room_event_views; preserva los checksums V001–V004 y rellena los participantes de partidas PREPARING anteriores. La reconexión recupera tablero confirmado, preparación, dados, plazo y acción de la misma partida. Una respuesta tardía no restaura una pertenencia perdida. La política de recuperación tras reiniciar Java sigue pendiente.

Flutter reutiliza los cuatro mapas, permite acomodo manual, intercambio y ayuda aleatoria, muestra una propuesta resaltada y ofrece Confirmar jugada/Omitir ronda. El tablero completo, el reloj y el chat son accesibles en 320×568, 390×844 y 844×390, con pruebas de texto 2×. La animación visual usa únicamente los dados recibidos.

Esta entrega no adjudica premios globales, puntos, victoria por meta/abandono ni votación por bloqueo. Eso corresponde a entrega 5; ranking, animaciones finales y revancha siguen pendientes. Verificación: 59 pruebas Java, pruebas Flutter de preparación/acciones/reintentos/reloj y recorrido real de seis canales contra el JAR con datos temporales. Los timeouts y las carreras se verifican con reloj controlado en Java.
