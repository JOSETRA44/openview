# Integración de la experiencia OpenView

Registro iniciado: 2026-11-12. Dispositivo: Infinix X6885, Android 16, arm64-v8a.

## Implementado

- Entrada propia `HomeActivity`, acceso al espacio y modo cámara, ajustes de visor existentes.
- Módulo cliente OpenXR independiente `android/experience`, con renderizado OpenGL ES.
- Compilación y artefacto del cliente en el workflow `build`.
- Prueba ADB reproducible `tools/device-smoke.py`, con contadores de frames, logs y captura.

## Pruebas e incidencias de integración

- Builds iniciales `4ff17df` y `58938c7`: Linux y Android correctos en CI. Cliente ARM64
  y x86_64 compilados; únicamente ARM64 ejecutado en el teléfono.
- `4ff17df`: escena continua, 61→844 frames en aproximadamente 13 s (~60 Hz), sin errores
  del cliente en la ventana de medición. Cámara: 6→791 frames de escena y 0→413 callbacks
  de cámara; los callbacks no garantizan que todos los frames se presenten.
- Inspección visual: composición estéreo con distorsión de visor, suelo y esculturas;
  vídeo trasero real con esculturas superpuestas. No se usó `hello_xr` para estas pruebas.
- `58938c7`: pantalla principal instalada y revisada; botón «Entrar al espacio» abre
  `dev.openview.experience` y produce una sesión OpenXR. Recentrado y modo cámara mediante
  botones de volumen producen los eventos correspondientes en logcat.
- Android rechazó inicialmente actualizar el runtime por almacenamiento insuficiente
  (551 MB libres). Eliminar los símbolos de depuración del APK por defecto redujo el
  artefacto de 42 253 309 a 13 215 397 bytes y permitió actualizar. No se borraron capturas
  ni datos del teléfono. Para depuración nativa se puede compilar con `-PkeepNativeSymbols`.

### Orientación de cámara

El usuario confirmó que el vídeo inicial aparecía girado aun sosteniendo el teléfono
horizontal. Camera2 entregó una matriz SurfaceTexture con rotación del sensor ya incluida;
faltaba corregir la rotación del display. Datos observados: sensor 90°, display 90°,
matriz con bloque XY `[0,-1;-1,0]` y traslación `[1,1]`. El cliente aplica −90° a las UV
antes de esa matriz. Se desactiva `SCALER_ROTATE_AND_CROP` automático cuando está disponible.

Cliente `2d0bf9f` instalado: captura inspeccionada con vídeo derecho y esculturas visibles;
544 frames enviados y 289 callbacks de cámara en la prueba de 10 s. El recorte central
preserva la proporción de la imagen. Las pruebas C++ verifican mapeos de UV en cuatro
rotaciones; eso no equivale a probar cuatro orientaciones en hardware.

### Cierre de sesión y vida del runtime

La prueba inicial de render no detectaba un fallo posterior: al salir o pausar, aparecía
`session closed`, pero después el hilo terminaba con SIGSEGV en `__cxa_thread_finalize`.
Se observaron varios cierres nativos en `dumpsys activity exit-info`.

El PC fallido coincide con el offset `0x275ae4` del destructor `jni::ScopedEnv::~ScopedEnv`
en la biblioteca con símbolos, tomando una base ya descargada. La implementación de
jnipp usa `static thread_local ScopedEnv`. Es evidencia consistente con un destructor TLS
apuntando a código descargado; no identifica por sí sola toda la secuencia de descarga
del loader/vendor. Android documenta `-Wl,-z,nodelete` como protección para esta clase de
problema en [sus notas del linker](https://android.googlesource.com/platform/bionic/+/master/android-changes-for-ndk-developers.md).

Se añadió esa opción al runtime Android. Sesiones, swapchains, cámara e IPC conservan
su destrucción normal; el código de la biblioteca permanece mapeado hasta terminar el
proceso. `tools/device-lifecycle.py` comprueba que el mismo proceso sobreviva a Inicio,
retorno y Atrás, y revisa los cierres y señales fatales.

**Verificado con runtime y cliente `2d0bf9f`:** mismo PID 25482 antes de pausar, después
de Inicio, al volver y tras Atrás. Dos sesiones READY, dos cierres, recentrado observado,
cero señales fatales. La primera repetición también conservó el proceso; el recolector
perdía la primera línea READY por rotación del buffer logcat del fabricante. Se corrigió
el recolector para conservar y deduplicar snapshots por etapa, sin vaciar logs del usuario.

**Permiso de cámara denegado:** se reprodujo la denegación persistente de Android,
el cliente registró `Camera permission denied; continuing in space mode` y siguió
renderizando 62→244 frames de espacio sin cámara. Al terminar se retiró la denegación
de prueba y se restauró el permiso. No se validó todavía una desconexión física de cámara
ni la recuperación de todos los errores del HAL.

## Estado al entregar el hito

Runtime y cliente instalados desde el commit `2d0bf9f`,
[build 43356408255](https://github.com/JOSETRA44/openview/actions/runs/43356408255):
Linux, Android y experiencia aprobados; pruebas de rotación/recorte C++ aprobadas.

Pruebas finales independientes, ventana solicitada de 25 s (más inicialización):

| Modo | Contadores inicial → final | Resultado |
|---|---|---|
| Espacio | 61 → 1568 frames estéreo | Continuidad, sin errores del cliente; captura inspeccionada |
| Cámara | 10 → 1482 frames estéreo; 0 → 754 callbacks de cámara | Vídeo vivo y objetos visibles, sin errores del cliente; captura inspeccionada |
| Ciclo de vida | PID 25482 estable; 2 inicios y 2 cierres | Inicio/retorno/Atrás y recentrado aprobados, sin SIGSEGV |
| Cámara denegada | 62 → 244 frames de espacio | Continúa en espacio sin cámara |

El teléfono queda en `HomeActivity`, sin una captura de sensores activa, con permiso de
cámara restituido y superposición habilitada. Volumen + recentra; Volumen − alterna cámara;
Atrás sale. Las dependencias y parches existentes de Monado no se descartaron ni cambiaron.

Siguiente trabajo: adaptar XRSLAM a VIT, repetir/ampliar sus secuencias offline y evaluar
build ARM; capturar el tablero físico para calibrar el teléfono; medir extrínsecos,
sincronización y rolling shutter antes de anunciar 6DoF móvil. El primer resultado offline
monocular está en [el experimento XRSLAM](../../tools/mono/xrslam-experiment.md).

## Evidencia conservada

Logs, resultados JSON y capturas de esta sesión están en `build/device-smoke/` (ignorado
por Git). Las capturas de cámara no se publican en el repositorio ni como artefactos CI.
Se conservan las capturas antiguas de sensores en `D:/openview-artifacts/captures`.

## Límites del hito

El seguimiento del teléfono sigue siendo 3DoF. La cámara se muestra como fondo monocular;
los objetos no están anclados al mundo físico. No se ha medido la latencia cámara-pantalla,
el error angular ni el confort con un visor puesto. Ver ADR-0004 para la separación de capas.
