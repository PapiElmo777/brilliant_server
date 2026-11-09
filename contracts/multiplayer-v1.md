# Multijugador — contrato v1

Implementación de las entregas 1–6 del plan, actualizada el 8 de noviembre de 2026. Las rutas HTTP de salas, ajustes, Listo y comienzo de preparación están expuestas. WebSocket, envío de chat e historial están disponibles. Preparación, rondas, premios, marcador, votación y resultado persistido están disponibles. Animaciones finales y revancha disponibles en entrega 6; evaluación completa/LAN corresponde a entrega 7. Cuentas conserva sus rutas existentes.

Los esquemas JSON Schema 2020-12 de cuerpos, respuestas y sobres de evento están en `multiplayer-v1.schema.json` (`$defs`). Los esquemas de sala, miembro, listado e instantánea describen la entrega actual; los sobres de eventos y el historial describen el canal actual. No sustituyen autorización ni validación de dominio. El esquema de match incluye preparación privada y rondas; el resultado final, premios, marcador y votación describen la entrega 5.

## Transporte y autoridad

HTTP `/api/v1`, Bearer en cabecera y WebSocket `/api/v1/rooms/{roomId}/events` en el mismo origen. TLS fuera de Debug. No enviar tokens en URL. La identidad procede de la sesión autenticada; ningún comando acepta identidad, puntos, premios, anfitrión o dados enviados por el cliente como autoridad.

Toda mutación de multijugador lleva `commandId` UUID en el cuerpo. El servidor guarda `(userId, commandId, operación, recurso, contenido canónico, respuesta)` en la misma transacción del efecto. Repetirlo devuelve la respuesta original; cambiar operación/recurso/contenido produce 409 COMMAND_CONFLICT. La unicidad de miembro usa cuenta, no token, por lo que refresh no añade participantes. Solo una pertenencia activa por cuenta. Implementado en SQLite V003 con índice único y transacciones BEGIN IMMEDIATE. Los recibos se guardan para comandos aceptados; una petición rechazada no produce efectos ni un recibo de éxito.

## Estados

Sala: LOBBY, PREPARING, PLAYING, FINISHED, CLOSED.
Partida: PREPARING, ROUND_ACTIVE, END_VOTE, FINISHED, CANCELLED.
Acción: PLACE o SKIP, una por participante/ronda. Con dados `[a,b]`, `dieIndex` 0 coloca a y usa b como ancla; 1 coloca b y usa a. Ninguna colocación confirmada puede deshacerse.

`rulesVersion: 1` referencia `catalog-v1.json`: cuatro mapas, coordenadas base cero, casillas `f1_c1`, reglas RED/YELLOW distintas, BLUE/WHITE iguales, GREEN libre, PURPLE hasta dos distintos, ORANGE_LOW 1–3 y ORANGE_HIGH 4–6. Los premios son por zona/mapa; puestos 4–6 dan cero. Todas las colocaciones tienen rango 1–6, adyacencia ortogonal a un número igual al otro dado y destino vacío. Preparación requiere exactamente las seis casillas iniciales con 1–6 sin repetidos y reglas válidas.

Meta con participantes iniciales: 2→26, 3→24, 4→22, 5–6→20. Permanece fija ante abandonos. Ranking: puntos descendentes, zonas descendentes, secuencia del último premio positivo ascendente. Completar con cero no cambia el momento de alcanzar la puntuación. Confirmado el 8 de noviembre de 2026: los abandonos van después de quienes siguen; el empate absoluto se resuelve por orden de ingreso. El ganador ocupa el primer puesto incluso si gana por abandono con menos puntos. El primer aceptante asume el anfitrión de revancha y se admiten jugadores nuevos (confirmado el 8 de noviembre). La política de reinicio de Java sigue pendiente.

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
- 409 PREPARATION_CLOSED, PREPARATION_ALREADY_CONFIRMED, CAPACITY_BELOW_OCCUPANCY, LEVEL_REQUIRED, HOST_STARTS_MATCH, ROOM_FULL, ROOM_ALREADY_STARTED, ALREADY_IN_ROOM, STALE_SETTINGS, COMMAND_CONFLICT, NOT_ENOUGH_PLAYERS, PLAYERS_NOT_READY, PREPARATION_PENDING, ROUND_EXPIRED, ACTION_ALREADY_RESOLVED, MATCH_FINISHED, VOTE_NOT_ACTIVE, VOTE_ACTIVE, BALLOT_ALREADY_CAST, REMATCH_CLOSED, REMATCH_NOT_ACCEPTED.
- 429 RATE_LIMITED con Retry-After.

## Renovación implementada

`POST /auth/refresh` con `{refreshToken}` devuelve el mismo formato de login y rota ambos tokens sin crear otra sesión. Flutter conserva secretos solo en memoria y renueva 60 segundos antes del vencimiento, con una petición en curso. Logout/borrado esperan esa petición. 401/403 invalidan rutas; una respuesta tardía no restaura identidad. 429 respeta Retry-After. Un fallo de red/5xx deja incierto si el secreto se consumió: no se reenvía y se conserva acceso hasta su caducidad o un 401. Al reiniciar proceso se requiere login.

## Verificación y reproducción

Cliente: `flutter test`, `flutter analyze`, `flutter test tool/export_multiplayer_catalog_test.dart`.
Servidor: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./mvnw test`.

Regenerar intencionalmente con `flutter test tool/export_multiplayer_catalog_test.dart --dart-define=UPDATE_MULTIPLAYER_CATALOG=true` y `flutter test test/multiplayer/rules_parity_test.dart --dart-define=UPDATE_MULTIPLAYER_CATALOG=true`. Copiar catálogo a `src/main/resources/multiplayer/` y corpus a `src/test/resources/multiplayer/` del servidor. Comparar copias byte a byte antes del commit. No modificar fixtures para encubrir diferencias del motor.


## Alcance verificado de lobby

Todas las rutas de sala responden 200 en éxito. GET /rooms enumera los lobbies, también los llenos, y devuelve currentRoomId para recuperar una pertenencia existente sin volver a unirse. GET /rooms/{id} solo es accesible para miembros activos de esa sala. Flutter consulta el listado cada tres segundos en primer plano. Dentro de una sala recibe eventos por WebSocket; deja de consultar GET periódicamente. En segundo plano cierra el canal y al volver recupera la instantánea.

Una vez enlazado el canal, la presencia de sala caduca tras 45 segundos sin latido de ese canal, por separado del heartbeat de cuenta. Consultar GET o cambiar Listo no prolonga una conexión perdida. Antes del primer canal se mantiene temporalmente la presencia HTTP de entrega 2. La renovación conserva el sid y el miembro. Logout/borrado retiran al miembro en la misma transacción de cuenta. La salida cambia anfitrión al conectado de mayor antigüedad y cierra una sala vacía. Los nombres e identidades de participación se conservan para el historial aunque se borre la cuenta. Estas comprobaciones cubren lobby/entrada a preparación; la victoria por abandono durante la partida está integrada en entrega 5.

POST /start crea una partida PREPARING, congela nivel, participantes y meta; no crea dados ni inicia el modo local. La interfaz muestra este estado sin permitir cambios de configuración. La preparación individual y las rondas están implementadas en entrega 4. En la entrega actual no se implementa una política de recuperación o cancelación de partidas en juego tras reiniciar Java.

Recorrido real: `flutter test test/multiplayer/rooms_api_e2e_test.dart --dart-define=RUN_ROOMS_API_E2E=true`. Requiere JAR compilado y Java 21; ambos se pueden seleccionar con ROOMS_SERVER_JAR y ROOMS_SERVER_JAVA. El recorrido usa siete repositorios Dart independientes, un puerto libre y SQLite/cuentas temporales, y termina su proceso Java. Verifica lobby de seis, séptimo rechazado, aislamiento de otra sala, permisos, seis canales, chat compartido, renovación/reconexión con la misma identidad, mismo identificador de preparación y transferencia de anfitrión; también verifica seis preparaciones privadas, dados/plazo comunes, colocación y omisiones, avance único, rechazo de ronda antigua y restauración del tablero al reconectar. No acredita seis teléfonos físicos.


## Canal e historial — entrega 3

V004 añade vínculo de canal y mensajes. Mantiene los checksums V001/V002/V003. Los eventos y mensajes se insertan con los cambios y recibos de comandos; el canal lee únicamente filas confirmadas y realiza los envíos fuera de la transacción SQLite. Se envía un lote de hasta 32 eventos ordenados por conexión, con comprobación de sesión, miembro, canal vigente y vencimiento del Bearer antes de cada lote; los latidos también se autorizan. La sustitución concurrente se decide por el vínculo almacenado en SQLite, no por el orden de ejecución de callbacks.

Primer frame: sobre de evento con type SNAPSHOT y data igual a GET /rooms/{id}. Después llegan eventos con secuencia creciente. Flutter ignora secuencias ya aplicadas, evita retroceder al recibir un recibo HTTP antiguo y ante un salto consulta GET y sustituye el canal para obtener una instantánea nueva. Mientras reconcilia, bloquea cambios de configuración, Listo, inicio y nuevos mensajes. Reintentar un comando aceptado conserva su commandId.

Cliente → servidor: exclusivamente `{"type":"PING"}` cada 10 segundos. Servidor → cliente: `{"type":"PONG","serverTime":"..."}`, sin alterar la secuencia de sala. Flutter detecta 25 segundos sin respuesta y reintenta con esperas de 1, 2, 4 y hasta 8 segundos. Al cambiar Bearer cierra el canal anterior y abre otro; las generaciones descartan respuestas tardías y serializan aperturas pendientes.

Cierres: 4001 sesión/token inválido, 4003 miembro retirado o presencia agotada, 4009 conexión sustituida. Un 4009 no provoca una pelea de reconexiones: el cliente muestra el estado y espera que el usuario pulse Actualizar. Los cortes transitorios reservan el lugar hasta 45 segundos y conservan Listo, mapa y rol; al vencer se aplica la salida existente una sola vez. La política de victoria por abandono durante rondas está integrada en entrega 5.

Límites del canal: entrada de hasta 256 caracteres de texto o 256 bytes binarios, salida hasta 64 KiB, decorador serializado con buffer máximo 128 KiB. Ocho trabajadores y cola de 64 tareas separan los envíos de los hilos HTTP; Tomcat tiene límites de escritura y cierre de 1,5 segundos. Un cliente lento se cierra/reconecta. No se admiten credenciales en query ni redirecciones en el cliente Dart. Origin usa la política existente de orígenes explícitos; el cliente nativo envía Bearer en cabecera.

Chat: texto plano de 1–300 puntos de código Unicode, sin controles salvo salto de línea. Solo Java crea SYSTEM (entrada, salida, cambio de anfitrión e inicio de preparación), con los nombres de participación. El usuario solo envía text y commandId; campos adicionales como kind se rechazan. Límite de 10 mensajes por cuenta cada 10 segundos, con 429 y Retry-After; reintentar un éxito previo no consume otro envío. Los mensajes pertenecen a la sala y conservan autor/nombre histórico tras borrar la cuenta.

La instantánea contiene los últimos 20 mensajes; GET /messages?before=N obtiene los 20 anteriores en orden ascendente y devuelve nextBefore. El cliente conserva hasta 200 mensajes en memoria y detiene la carga al alcanzar ese límite. Muestra no leídos del historial cargado, conserva el borrador ante un resultado incierto y lo limpia al confirmar el mismo comando. El panel se cierra con el teclado abierto; logout/caducidad desmontan también el chat. La burbuja comparte la franja de controles del lobby sin superponerse al mapa.

El outbox conserva estado público con ownBoard vacío; V005 almacena por separado la proyección privada de cada participante en la misma transacción y secuencia. HTTP y WebSocket envían solo el tablero del solicitante. Los eventos atrasados usan su proyección histórica, sin mezclar un tablero futuro con dados anteriores. Votación, premios y resultado persistido se integran en entrega 5. Animaciones y revancha se integran en entrega 6. La política de reinicio de Java permanece pendiente.

Referencias de implementación: [WebSocket en Spring Framework 6.2](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/server.html), [WebSocket.connect de Dart](https://api.dart.dev/dart-io/WebSocket/connect.html) y [límites de Tomcat 10.1](https://tomcat.apache.org/tomcat-10.1-doc/web-socket-howto.html). No se actualizaron Spring Boot ni las versiones de reglas por esta entrega.


## Preparación y rondas — entrega 4

PUT /matches/{id}/preparation confirma exactamente seis posiciones iniciales con los números 1–6 sin repetir. Java valida casillas y restricciones del mapa congelado; la confirmación es definitiva. Se publica PREPARATION_CONFIRMED sin revelar posiciones ajenas. La primera ronda empieza únicamente cuando todos los participantes activos confirman; no se añade un plazo a la preparación.

Cada ROUND_STARTED contiene id, number, dice, startedAt, deadline, resolvedMemberIds y ownAction. Java genera los dos dados una sola vez y calcula ambos instantes desde el mismo reloj: deadline = startedAt + 60 segundos. match incluye preparedMemberIds, ownPreparationConfirmed y ownBoard (objeto celda→entero). El reloj Flutter parte de serverTime y avanza con tiempo monotónico; al llegar a cero bloquea nuevos envíos, mientras Java decide el vencimiento definitivo.

PLACE exige dieIndex 0/1 y cellId: el otro dado es el ancla. SKIP no acepta esos campos. Java comprueba pertenencia, ronda vigente, plazo, destino, adyacencia y restricciones. Una acción aceptada es inmutable. Toda propuesta visual permanece local hasta confirmar; timeout la descarta y registra SKIP con reason TIMEOUT. ownAction contiene kind, dieIndex, cellId y reason PLAYER/TIMEOUT, o null si está pendiente. Los campos dieIndex/cellId son null en SKIP.

La transacción serializada y los índices únicos resuelven la carrera entre última acción y vencimiento: una sola acción por participante/ronda y una sola ronda siguiente. Todos los activos deben resolver antes del avance anticipado. El temporizador del servidor revisa vencimientos cada 250 ms y también antes de comandos. Los recibos conservan su instantánea privada original; el orden de las propiedades de placements no cambia la identidad canónica del comando.

V005 agrega match_players, match_rounds, round_actions y room_event_views; preserva los checksums V001–V004 y rellena los participantes de partidas PREPARING anteriores. La reconexión recupera tablero confirmado, preparación, dados, plazo y acción de la misma partida. Una respuesta tardía no restaura una pertenencia perdida. La política de recuperación tras reiniciar Java sigue pendiente.

Flutter reutiliza los cuatro mapas, permite acomodo manual, intercambio y ayuda aleatoria, muestra una propuesta resaltada y ofrece Confirmar jugada/Omitir ronda. El tablero completo, el reloj y el chat son accesibles en 320×568, 390×844 y 844×390, con pruebas de texto 2×. La animación visual usa únicamente los dados recibidos.

La entrega 4 no adjudicaba premios globales ni finales: esas capacidades se integran a continuación en entrega 5. Animaciones finales y revancha se integran en entrega 6. Verificación: 59 pruebas Java, pruebas Flutter de preparación/acciones/reintentos/reloj y recorrido real de seis canales contra el JAR con datos temporales. Los timeouts y las carreras se verifican con reloj controlado en Java.


## Premios, continuidad y votación — entrega 5 (8 de noviembre de 2026)

V006 añade match_progress, zone_claims, end_votes y end_ballots sin modificar V001–V005. Un reclamo es único por partida/zona/participante; su puesto global también es único. La colocación, acción, premio, puntos, mensajes, resultado y proyecciones privadas se guardan en la misma transacción BEGIN IMMEDIATE. El orden aceptado por Java asigna los puestos; cuarto, quinto y sexto reciben +0, cuentan la zona y no alteran scoreSequence (último premio positivo). Los reintentos aceptados devuelven el recibo original sin puntuar dos veces. Si falla el guardado de un evento se revierte también la colocación.

match incorpora scores, claims, zones, blockedRounds y endVote. scores publica memberId, username, joinOrder, abandoned (0/1), points, completedZones y scoreSequence; claims publica zoneId, memberId, place, points y sequence. zones contiene zoneId, nextPlace y nextPoints; tras seis reclamos nextPlace es 7 y nextPoints es 0. Los tableros ajenos siguen siendo privados. Flutter muestra puntuación propia/meta y un marcador horizontal con los rivales; mantener pulsada una zona refleja los puestos consumidos.

Al alcanzar la meta, Java fija FINISHED, ganador y ranking antes de aceptar otra acción, cierra la ronda sin crear otra y emite MATCH_FINISHED. result es inmutable: winnerMemberId nullable, reason TARGET/ABANDONMENT/END_VOTE/NO_PARTICIPANTS, ranking con position y finishedAt. Las salidas posteriores no reordenan el resultado. La entrega 6 presenta animaciones y ranking con Revancha/Salir al menú.

La meta original y los reclamos se conservan tras salidas, logout, borrado o agotamiento de presencia. Se deja de esperar a quien sale y se transfiere el anfitrión por la política existente. Si queda uno, gana por abandono incluso desde preparación o durante votación; si no queda nadie, se cierra sin ganador. La expiración de varios miembros en una misma revisión los retira como lote antes de determinar el ganador, evitando una victoria accidental por el orden de las filas.

Al cerrar una ronda, Java comprueba ambos dados de todos los activos. Una colocación aceptada o un movimiento disponible rompe la racha, aunque se omita o venza el reloj. Tres rondas consecutivas bloqueadas abren END_VOTE con ID propio y plazo exacto de 60 segundos. Las rondas quedan pausadas. GET /matches/{id}/end-vote devuelve {endVote: objeto|null}; PUT /matches/{id}/end-vote/ballot exige commandId, voteId y approve boolean. Solo miembros activos votan; un voto confirmado es definitivo y los reintentos conservan su ID de comando.

endVote contiene id, startedAt, deadline, outcome nullable APPROVED/REJECTED/EXPIRED, eligibleMemberIds, required y ballots ({memberId, approve:0|1}). Se conserva la última votación cerrada hasta abrir otra. Se necesitan floor(activos/2)+1 afirmativos: 2→2, 3→2, 4→3, 5→3, 6→4. No votar no reduce el denominador. Al alcanzar mayoría se fija el resultado por ranking. Si los votos negativos hacen imposible la mayoría, se rechaza; si vence sin alcanzarla, expira. En ambos casos se crea una ronda nueva con 60 segundos, se reinicia la racha y se requieren otras tres rondas bloqueadas para volver a ofrecer votar.

Reglas confirmadas por el usuario el 8 de noviembre: pausa durante votación, continuación sin mayoría, nueva oferta tras otras tres rondas bloqueadas, retirada del voto de quien abandona y recálculo de mayoría. Con un restante prevalece victoria por abandono. Cambiar anfitrión o reconectar conserva la votación. Las instantáneas, recibos y eventos autorizados restauran voto, plazo, marcador y resultado.

Verificación: 75 pruebas Java, incluidas carreras de seis reclamos, puntos 4–6 a cero, reversión transaccional, meta, orden final, mayorías 2–6, voto inmutable, rechazo/timeout, racha, abandono y expiración simultánea. Votación y resultado se restauran por WebSocket real; migración V005→V006 y reapertura preservan tablero/ronda/acción y checksums. Recorrido Dart→JAR con seis canales juega hasta completar zonas reales, comprueba premios/privacidad comunes y termina por abandono conservando reclamos. Los casos de bloqueo usan posiciones válidas preparadas en una base temporal y reloj controlado. No equivale a seis teléfonos físicos.


## Final y revancha — entrega 6 (8 de noviembre de 2026)

Solo winnerMemberId recibe CelebracionVictoria, reutilizada del modo local con el mapa y puntos confirmados. Los demás ven un láser, impacto y caída de su nave con «Usted ha quedado en N lugar». Ver ranking omite la secuencia; movimiento reducido abre directamente el resultado sin láser ni caída. La animación no envía comandos ni altera ranking/puntos y no se repite al recibir eventos o reconectar dentro de la misma pantalla/sesión de sala. Una partida con ID nuevo puede mostrar otra animación.

Ranking con ganador, motivo, posiciones/puntos/zonas/abandonos congelados y fila propia resaltada. Ofrece Revancha y Salir al menú principal; esta última confirma la salida y espera a Java antes de volver al menú. Chat permanece accesible. Layout verificado en tres tamaños y texto 2×.

POST /matches/{id}/rematch acepta únicamente {commandId}. Exige miembro activo de esa participación finalizada. Su recibo idempotente conserva la instantánea autorizada; otro commandId del mismo aceptante no crea otra aceptación. Java publica REMATCH_ACCEPTED con mensaje de sistema. Antes de terminar o después de iniciar la siguiente partida responde REMATCH_CLOSED; un participante que aún no acepta recibe REMATCH_NOT_ACCEPTED si intenta cambiar Listo en el nuevo lobby.

Confirmado por el usuario el 8 de noviembre: el primer aceptante es el anfitrión; el anterior no recupera el rol si acepta después. El lobby aparece en el listado y admite jugadores nuevos con las mismas reglas de cupo/entrada. La capacidad máxima se conserva, el mapa vuelve a estar sin elegir y Listo se reinicia al aceptar (cambiar mapa/capacidad después sigue conservando Listo). Con un aceptante no se puede iniciar; desde dos presentes, mapa elegido y todos los invitados listos se crea otro ID de partida y se recalcula la meta. Preparación, tableros, dados, acciones, reclamos, puntos, votaciones y resultado son nuevos. El historial de sala se conserva, con mensajes de sistema de revancha e inicio como separadores.

La misma sala puede mostrar resultados antiguos y lobby/partida nueva a miembros distintos. V007 añade view_match_id por miembro, rematch_source_id de sala, rematch_acceptances y room_member_events, sin alterar V001–V006. La ocupación del lobby y sus permisos cuentan solo miembros que aceptaron o entraron como nuevos. Quienes siguen en resultados no consumen cupo de la siguiente partida y conservan su propio resultado/tablero, incluso cuando ya empezó otra. Entonces match.rematch.available pasa a false; deben salir para entrar de nuevo como jugadores nuevos. No se expone el tablero de otro participante.

match.rematch es null durante el juego y {available, acceptedCount} al consultar un resultado. acceptedCount cuenta aceptaciones registradas de esa partida; la ocupación actual procede de room.members en el lobby. Se almacena la instantánea autorizada de cada miembro en la misma secuencia del evento, por lo que un evento atrasado no mezcla aceptación, resultado o tablero futuros. GET y la primera instantánea del canal seleccionan la vista persistida del miembro. El outbox común continúa sin tableros privados. Eventos anteriores a V007 conservan la proyección histórica V005.

Al salir el anfitrión del nuevo lobby o partida, el rol pasa a un aceptante/entrante actual; quienes ven resultados anteriores no participan de esa transferencia. La lista de participantes y sus contadores muestran solo la partida/vista correspondiente. Si se vacía ese lobby pero quedan personas en resultados, estos conservan la oportunidad de abrirlo de nuevo; si no queda nadie, la sala se cierra. Salir desde un resultado anterior no abandona la nueva partida, porque no pertenece a su lista de participantes. Al iniciar se cierra la aceptación del resultado anterior.

Verificación: 83 pruebas Java, incluidas aceptación parcial, cupo con jugadores nuevos, carrera de aceptaciones, permisos/recibos, vistas y eventos históricos diferenciados, transferencia y migración V006→V007/reapertura. Flutter verifica exclusividad de victoria/derrota, puesto, salto, movimiento reducido, no repetición, revancha incierta y salida al menú; capturas de ranking/animaciones. Recorrido real adicional de siete clientes/seis canales: premios, final por abandono y revancha de tres con otro mapa, meta 24, preparación limpia y nueva ronda común número 1. Datos temporales, sin afirmar prueba en seis teléfonos físicos.
