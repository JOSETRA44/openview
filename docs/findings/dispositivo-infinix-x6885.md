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
