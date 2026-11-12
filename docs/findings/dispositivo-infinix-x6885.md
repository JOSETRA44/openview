# Mediciones en dispositivo: Infinix X6885

Helio G99 (mt6789), GPU Mali, Android 16 (API 36), panel 1080×2400 con modos 60/90/120/144 Hz
(todo medido a 60 Hz), IMU ST LSM6DSO. Gama media: representativo del hardware objetivo.

App de prueba: `hello_xr` (Vulkan) de Khronos. Runtime: APK de OpenView con los parches 0001 y
0002. Fechas: 2026-11-11 y 2026-11-12. Niveles de evidencia según [README.md](README.md).

Fuentes: el resumen por segundo del driver (`debug.xrt.ANDROID_SENSORS_LOG=debug`), logcat del
sistema y `dumpsys SurfaceFlinger --timestats` para la capa `SurfaceView[Monado]`.

## El fabricante congela el runtime si no tiene ventana propia

**Verificado**, reproducido cinco veces. Sin el permiso "mostrar sobre otras apps", exactamente
5.0 s después de arrancar:

```
Usf_Hiber/importantScene: isTrafficActive uid:10700 speed :0
Usf_Hiber/appStateMachine: uid:10700 enter FrozenState
Usf_Hiber/stateManager: freeze uid: 10700 dev.openview.runtime
```

El proceso entero queda congelado: ni sensores ni compositor. `Usf_Hiber` es un hibernador
propio de Transsion (Infinix, Tecno, itel), ajeno a AOSP. Ocurre aunque el servicio esté en
primer plano y el sistema lo clasifique como enlazado a la app visible (`uidState: BTOP`).

Qué lo evita, variando una cosa cada vez:

| Variable | Resultado |
|---|---|
| Permiso de notificaciones concedido y bucket de uso ACTIVE | Se congela igual |
| Lista blanca de optimización de batería (`deviceidle whitelist`) | Se congela igual |
| Permiso `SYSTEM_ALERT_WINDOW` ("mostrar sobre otras apps") | **No se congela** (88 de 90 s con datos continuos) |

Con ese permiso Monado crea una ventana propia en vez de dibujar en una superficie de la app
cliente. La hipótesis anterior ("basta con abrir la app y aceptar notificaciones") quedó
**refutada**.

Además, sin ventana propia el compositor avisa de frame perdido en casi todos los frames
(~55 por segundo); con ella, 24–29 avisos por minuto.

Consecuencia aplicada: la app avisa con una notificación cuando falta el permiso
(`OpenViewApplication.kt`), verificado quitándolo y devolviéndolo.

**Límite**: el permiso es obligatorio en la práctica, al menos en dispositivos Transsion.
**Hipótesis**: otros fabricantes con gestores de energía agresivos (Xiaomi, Oppo, Vivo, Huawei)
se comportan parecido; sin probar.

## Sensores

**Verificado**:

| Magnitud | Valor |
|---|---|
| Tasa efectiva del giroscopio | 203–204 Hz (se piden 180 Hz: 3× el refresh) |
| Antigüedad de la muestra al procesarla | media 1.0–1.7 ms, máx. 6 ms |

La conversión `CLOCK_BOOTTIME` → `CLOCK_MONOTONIC` del parche 0001 es correcta aquí: una base
de tiempo equivocada daría antigüedades negativas o de segundos.

## Latencia de pantalla y predicción

**Verificado** con SurfaceFlinger (3295–5024 frames por corrida):

| Magnitud | Valor |
|---|---|
| Frames por segundo | 58.5–59.0 |
| Intervalo entre presentaciones | 16 ms: 94 %; 33 ms: 6 % |
| Frames descartados | ~6 % |
| De entregar el buffer a mostrarlo (`post2present`) | mediana 36 ms, rango 26–44 ms, repartido de forma casi uniforme |
| De latch de SurfaceFlinger a mostrarlo | 25–26 ms |

Dos conclusiones:

1. Entre presentar y mostrar pasan unos dos periodos de refresco. Monado asumía 4 ms (valor
   pensado para un visor de escritorio en modo directo), así que predecía las poses para un
   instante ~30 ms anterior al real.
2. El reparto uniforme de 26 a 44 ms (un periodo entero de ancho) indica que el compositor no
   está enganchado en fase al vsync: Monado usa en Android su pacer "falso"
   (`u_pacing_compositor_fake.c`), que estima el vsync con un temporizador, y no consulta
   `Choreographer`. De ahí también el 6 % de frames que llegan un vsync tarde.

Horizonte de predicción pedido por la app y el compositor (instante pedido − ahora):

| Modelo de latencia | mín. | media | máx. | Recortadas por el límite |
|---|---|---|---|---|
| 4 ms (Monado sin cambios) | 6 ms | 17 ms | 32 ms | 0 % con límite de 50 ms |
| 35 ms forzado por propiedad | 36 ms | 47 ms | 64 ms | 43 % con límite de 50 ms |
| 2 periodos (parche 0002), límite 83 ms | 34 ms | 46 ms | 63 ms | 0 de 6060 |

El primer límite de predicción (3 periodos, 50 ms) se eligió con datos del modelo de 4 ms y
resultó corto. El actual es 5 periodos acotado a 40–100 ms.

**No medido**: el error angular real ni la latencia percibida. Haría falta una cámara externa
o un banco de pruebas con movimiento conocido.

## Compositor enganchado al vsync (parche 0003)

**Verificado**, 60 s por corrida, misma app y mismo método:

| | Pacer por temporizador | Con vsync de `Choreographer` |
|---|---|---|
| Frames descartados | ~6 % | 0.2 % |
| Intervalos de 16 ms | 94 % | 100 % |
| De entregar el buffer a mostrarlo | 26–44 ms, casi uniforme | 29–32 ms, mediana 31 |
| Avisos de frame perdido del compositor | 24–29 por minuto | 20 por minuto |

La latencia queda en 31 ms: los 25–26 ms fijos de SurfaceFlinger a pantalla más ~5 ms de
espera hasta que SurfaceFlinger toma el buffer. El modelo de dos periodos del parche 0002
(33.3 ms) acierta con 2 ms de error.

Desplazar la fase con `XRT_COMPOSITOR_ANDROID_VSYNC_OFFSET_MS` para recortar esa espera:

| Desplazamiento | Frames descartados | Latencia mediana |
|---|---|---|
| 0 (por defecto) | 0.2 % | 31 ms |
| +3 ms | 25.8 % | 28 ms |
| +5 ms | 13.3 % | 42 ms |
| +7 ms | 0.0 % | 40 ms |

El valor por defecto ya está junto al límite: hay unos 3 ms de margen antes de perder el vsync.
**Hipótesis**: ese margen alcanza con una app exigente; solo se probó `hello_xr`.

## Cámara e IMU (fase 2)

**Verificado** con `tools/sensor-recorder`, captura de 8 s con el teléfono quieto:

| Magnitud | Valor |
|---|---|
| Fuente de timestamp de la cámara | `REALTIME`, nivel de hardware `FULL` |
| Latencia de captura suponiendo reloj de arranque | 78–86 ms, siempre positiva: la base declarada es cierta |
| `CLOCK_BOOTTIME` − `CLOCK_MONOTONIC` | 119 974 s (33 h): confundirlos no es un error pequeño |
| Cámara | 30.00 fps, sin saltos |
| **Rolling shutter** (`SENSOR_ROLLING_SHUTTER_SKEW`) | **29.4 ms** a 640×480 |
| Exposición en interior | 30 ms |
| Giroscopio y acelerómetro a tasa máxima | 406 Hz, p99 del intervalo igual a la mediana; 1 salto de 9.9 ms en 8 s |
| Canal directo de sensores | No soportado |
| Intrínsecos y distorsión declarados por el dispositivo | No |
| Estabilización óptica | No tiene |
| Cámaras | 2 (trasera y frontal), sin cámaras físicas agrupadas: no hay estéreo |

Consecuencias para el seguimiento 6DoF:

- La lectura del sensor ocupa casi todo el periodo del frame. Un tracker que suponga
  obturador global verá la imagen deformada con cualquier giro: modelar el rolling shutter
  es obligatorio, no una mejora.
- Con 30 ms de exposición hay desenfoque de movimiento fuerte. Habrá que limitar la
  exposición y aceptar más ganancia.
- Los intrínsecos hay que calibrarlos por dispositivo.
- La regularidad del IMU y la base de tiempo compartida son buenas noticias.

### Capturas en movimiento

**Verificado**, tres capturas a mano de 17–23 s con giros de ~1 rad/s eficaz (hasta 4.6 rad/s):
30.00 fps y 406 Hz sin un solo salto en ninguna, mismo rolling shutter.

| Captura | Brillo medio | Exposición | Velocidad de imagen mediana | Correlación con el giroscopio |
|---|---|---|---|---|
| 144821 | 57 | 30 ms | 42 px/s | 0.17 |
| 145137 | 56 | 20–30 ms | 42 px/s | 0.21 |
| 145427 | 129 | 10–30 ms | 90 px/s | 0.68 |

A 1 rad/s la imagen debería desplazarse unos 250 px/s a esta resolución. En las dos capturas
oscuras la correlación de fase entre frames mide una sexta parte: con 30 ms de exposición el
desenfoque de movimiento deja la imagen sin estructura que seguir.

**Límite**: con la exposición automática de este teléfono en interior, los frames tomados
durante un giro normal de cabeza no sirven para seguimiento visual. La grabadora ahora fija
la exposición en 5 ms como máximo.

### Desfase cámara–IMU

**Verificado en una sola captura** (145427, correlación 0.68): el giroscopio va 25 ms por
delante del timestamp de la cámara. El timestamp marca el inicio de la exposición de la
primera fila; el contenido de la imagen corresponde en promedio a media exposición y media
lectura después, 24.7 ms con los valores de esa captura. El residuo es 0.3 ms.

**Hipótesis**: los relojes de cámara e IMU de este dispositivo no tienen desfase propio y
todo el retardo aparente lo explican exposición y rolling shutter. Un dato con correlación
moderada no lo establece; hay que repetirlo con exposición fija.

## Térmica

**Verificado**, `hello_xr` durante ~8 min cargando por USB: piel estable en 34.6–35.9 °C,
CPU 38–44 °C, estado térmico 0.

**Límite**: `hello_xr` dibuja unos pocos cubos. No dice nada de una aplicación exigente.

## Registro del sistema

**Verificado**: `persist.log.tag=I`. El sistema descarta toda línea por debajo de INFO de
cualquier app, aunque sea depurable y aunque se cambie `log.tag`. Los diagnósticos que deban
funcionar en dispositivos de usuarios tienen que emitirse a nivel INFO o superior.

## Otros

- Sin calibración de visor guardada se usa la distorsión de Cardboard V1 por defecto.
- El driver Vulkan de Mali rechaza varios formatos de imagen externa al sondear
  (`VK_ERROR_FORMAT_NOT_SUPPORTED`); el arranque continúa con los que sí soporta.
