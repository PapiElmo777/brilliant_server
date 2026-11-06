# Multijugador — contrato v1

Implementación de las entregas 1 y 2 del plan del 5 de noviembre de 2026. Las rutas HTTP de salas, ajustes, Listo y comienzo de preparación están expuestas. Las rutas de juego, chat, votación, revancha y WebSocket siguen pendientes. Cuentas conserva sus rutas existentes.

Los esquemas JSON Schema 2020-12 de cuerpos, respuestas y sobres de evento están en `multiplayer-v1.schema.json` (`$defs`). Los esquemas de sala, miembro, listado e instantánea describen la entrega actual; los sobres de eventos reservan la siguiente. No sustituyen autorización ni validación de dominio. Los campos internos de match se ampliarán con las rondas y resultados.

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
| POST /rooms/{id}/messages | text (1–300 caracteres de texto plano) | 3 |
| PUT /matches/{id}/preparation | placements (objeto celda→entero) | 4 |
| POST /matches/{id}/rounds/{roundId}/actions | kind (PLACE/SKIP); PLACE exige dieIndex (0/1), cellId; SKIP los omite | 4 |
| GET /matches/{id}/end-vote | — | 5 |
| PUT /matches/{id}/end-vote/ballot | voteId, approve (boolean) | 5 |
| POST /matches/{id}/rematch | — | 6 |

Configuración lleva revisión para detectar cambios antiguos; la revisión global de eventos no invalida jugadas de rivales. PATCH reemplaza capacidad y nivel juntos; nivel aleatorio es una decisión del servidor y su reintento no vuelve a sortear. El anfitrión inicia desde dos presentes, mapa elegido y todos los invitados listos; su botón expresa su disponibilidad. Regla confirmada por el usuario: conservar Listo cuando cambie mapa o capacidad. El invitado puede quitar Listo. Se rechaza una capacidad menor que la ocupación actual.

## Instantáneas y eventos

Instantánea: `{protocolVersion:1, rulesVersion:1, roomId, sequence, serverTime, room, match, messages}`. `room`: id, name, hostMemberId, capacity, levelId nullable, revision, state, members (memberId, userId, username, joinOrder, ready, connected). `match` es null en lobby; después contiene id, state, levelId, target, participants, ownBoard, round y result. Solo se envía el tablero del solicitante. `round`: id, dice de dos enteros, deadline (UTC ISO-8601), resolvedMemberIds. El resultado final será inmutable.

Evento: `{protocolVersion:1, roomId, sequence, type, serverTime, data}`. Secuencia entera estrictamente creciente por sala, persistida con los efectos. Enviar después del commit. La primera respuesta del canal es instantánea; evento con secuencia ≤ última se ignora; un salto solicita GET de instantánea y bloquea comandos hasta reconciliar. Renovación sustituye el canal con nuevo Bearer manteniendo cuenta/miembro; una conexión nueva invalida la anterior. Detalles de autorización, buffers, latidos y plazo de 45 segundos se implementan/verifican en entrega 3.

Tipos reservados: MEMBER_JOINED, MEMBER_LEFT, HOST_CHANGED, SETTINGS_CHANGED, READY_CHANGED, MATCH_STARTED, PREPARATION_CONFIRMED, ROUND_STARTED, ACTION_RESOLVED, ZONE_CLAIMED, MESSAGE_CREATED, END_VOTE_OPENED, END_VOTE_UPDATED, END_VOTE_RESOLVED, MATCH_FINISHED. Cada evento ofrece la instantánea autorizada en `data`; MESSAGE_CREATED incluye mensaje `{id,sequence,kind:PLAYER|SYSTEM,authorMemberId,text,serverTime}`. Los mensajes SYSTEM se generan exclusivamente en Java.

## Errores

Formato existente `{error:{code,...}}`; el cliente decide por code, nunca por texto.

- 400 VALIDATION_ERROR, INVALID_PREPARATION, INVALID_MOVE, INVALID_DICE.
- 401 UNAUTHORIZED, ACCESS_TOKEN_EXPIRED, SESSION_INVALID.
- 403 NOT_ROOM_MEMBER, HOST_ONLY.
- 404 ROOM_NOT_FOUND, MATCH_NOT_FOUND.
- 409 CAPACITY_BELOW_OCCUPANCY, LEVEL_REQUIRED, HOST_STARTS_MATCH, ROOM_FULL, ROOM_ALREADY_STARTED, ALREADY_IN_ROOM, STALE_SETTINGS, COMMAND_CONFLICT, NOT_ENOUGH_PLAYERS, PLAYERS_NOT_READY, PREPARATION_PENDING, ROUND_EXPIRED, ACTION_ALREADY_RESOLVED, MATCH_FINISHED, VOTE_NOT_ACTIVE.
- 429 RATE_LIMITED con Retry-After.

## Renovación implementada

`POST /auth/refresh` con `{refreshToken}` devuelve el mismo formato de login y rota ambos tokens sin crear otra sesión. Flutter conserva secretos solo en memoria y renueva 60 segundos antes del vencimiento, con una petición en curso. Logout/borrado esperan esa petición. 401/403 invalidan rutas; una respuesta tardía no restaura identidad. 429 respeta Retry-After. Un fallo de red/5xx deja incierto si el secreto se consumió: no se reenvía y se conserva acceso hasta su caducidad o un 401. Al reiniciar proceso se requiere login.

## Verificación y reproducción

Cliente: `flutter test`, `flutter analyze`, `flutter test tool/export_multiplayer_catalog_test.dart`.
Servidor: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./mvnw test`.

Regenerar intencionalmente con `flutter test tool/export_multiplayer_catalog_test.dart --dart-define=UPDATE_MULTIPLAYER_CATALOG=true` y `flutter test test/multiplayer/rules_parity_test.dart --dart-define=UPDATE_MULTIPLAYER_CATALOG=true`. Copiar catálogo a `src/main/resources/multiplayer/` y corpus a `src/test/resources/multiplayer/` del servidor. Comparar copias byte a byte antes del commit. No modificar fixtures para encubrir diferencias del motor.


## Alcance verificado de lobby

Todas las rutas de sala responden 200 en éxito. GET /rooms enumera los lobbies, también los llenos, y devuelve currentRoomId para recuperar una pertenencia existente sin volver a unirse. GET /rooms/{id} solo es accesible para miembros activos de esa sala. Flutter consulta listado o instantánea cada tres segundos mientras esta pantalla está en primer plano; pausa al pasar a segundo plano. El canal WebSocket y el chat no están implementados todavía.

La presencia de sala caduca tras 45 segundos sin consultar su instantánea, por separado del heartbeat de cuenta. La renovación conserva el sid y el miembro. Logout/borrado retiran al miembro en la misma transacción de cuenta. La salida cambia anfitrión al conectado de mayor antigüedad y cierra una sala vacía. Los nombres e identidades de participación se conservan para el historial aunque se borre la cuenta. Estas comprobaciones cubren lobby/entrada a preparación; las reglas de victoria por abandono durante la partida corresponden a la entrega 5.

POST /start crea una partida PREPARING, congela nivel, participantes y meta; no crea dados ni inicia el modo local. La interfaz muestra este estado sin permitir cambios de configuración. La preparación individual y las rondas se integran en entrega 4. En la entrega actual no se implementa una política de recuperación o cancelación de partidas en juego tras reiniciar Java.

Recorrido real: `flutter test test/multiplayer/rooms_api_e2e_test.dart --dart-define=RUN_ROOMS_API_E2E=true`. Requiere JAR compilado y Java 21; ambos se pueden seleccionar con ROOMS_SERVER_JAR y ROOMS_SERVER_JAVA. El recorrido usa siete repositorios Dart independientes, un puerto libre y SQLite/cuentas temporales, y termina su proceso Java. Verifica lobby de seis, séptimo rechazado, aislamiento de otra sala, permisos, mismo identificador de preparación y transferencia de anfitrión; no acredita dados compartidos ni seis teléfonos físicos.
