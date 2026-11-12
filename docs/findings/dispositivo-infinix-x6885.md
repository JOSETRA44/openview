# Mediciones en dispositivo: Infinix X6885

Helio G99 (mt6789), GPU Mali, Android 16 (API 36), panel 1080×2400 con modos 60/90/120/144 Hz
(medido a 60 Hz), IMU ST LSM6DSO. Gama media: representativo del hardware objetivo.

App de prueba: `hello_xr` (Vulkan) de Khronos. Runtime: APK de OpenView con el parche 0001.
Fechas: 2026-11-11 y 2026-11-12. Niveles de evidencia según [README.md](README.md).

## Sensores y predicción de pose

**Verificado** (resumen por segundo del driver, 4 ventanas consecutivas):

| Magnitud | Valor |
|---|---|
| Tasa efectiva del giroscopio | 203–204 Hz (se piden 180 Hz: 3× el refresh) |
| Antigüedad de la muestra al procesarla | media 1.2–1.4 ms, máx. 4.5 ms |
| Consultas de pose | 106–114 por segundo |
| Horizonte pedido (instante pedido − ahora) | mín. 6 ms, media 16.5 ms, máx. 30 ms |
| Consultas recortadas por el límite de predicción | 0 |

Consecuencias:

- La conversión `CLOCK_BOOTTIME` → `CLOCK_MONOTONIC` es correcta en este dispositivo: una base
  de tiempo equivocada daría antigüedades negativas o de segundos, no de 1 ms.
- El límite de predicción (3 intervalos de frame, 50 ms a 60 Hz) deja 20 ms de margen sobre el
  máximo observado. No interfiere en operación normal.
- Antes del parche el driver devolvía la última orientación sin extrapolar, es decir, con esos
  ~16.5 ms de retraso. A 100 °/s son ~1.7° de error. **Hipótesis**: la mejora es perceptible;
  no se ha comparado visualmente ni medido el error angular.

## Térmica

**Verificado**, con el APK de Monado previo, `hello_xr` durante ~8 min, cargando por USB:
temperatura de piel estable en 34.6–35.9 °C, CPU 38–44 °C, estado térmico 0 todo el tiempo.

**Límite**: `hello_xr` dibuja unos pocos cubos. Esto dice que el runtime en reposo no calienta;
no dice nada de una aplicación real.

## El fabricante congela el runtime

**Verificado** en logcat. Secuencia con OpenView recién instalado por `adb` y nunca abierto:

1. `ActivityManager: Background started FGS: Allowed` — el servicio arranca en primer plano.
2. `NotificationService: Suppressing notification from package dev.openview.runtime by user
   request` — su notificación no se muestra: la app no tiene permiso de notificaciones.
3. 4 s después: `Usf_Hiber/importantScene: isTrafficActive uid:10699 speed :0`,
   `enter FrozenState`, `freeze uid: 10699 dev.openview.runtime`, y el sistema deja de
   entregar sensores a ese uid (`p_sensor`, `Proxyed UIDs`).
4. El driver y el compositor dejan de registrar actividad en ese mismo instante.

Estado comparado en ese momento:

| | OpenView | Monado (abierto a mano antes) |
|---|---|---|
| Bucket de uso (`am get-standby-bucket`) | 50 (RARE) | 10 (ACTIVE) |
| `POST_NOTIFICATION` | ignore | — |
| Lista blanca de batería | no | no |

`Usf_Hiber` es un hibernador propio de Transsion (Infinix, Tecno, itel), ajeno a AOSP.

**Hipótesis**: basta con que el usuario abra la app una vez y conceda notificaciones.
Prueba pendiente: variar por separado permiso de notificaciones, bucket y lista blanca de
batería, y ver cuál evita `FrozenState`.

**Límite** para el producto: un servicio de runtime sin interfaz visible es justo lo que los
gestores de energía de los fabricantes están hechos para matar. La app necesita un paso de
configuración inicial que lo compruebe y lo explique; no se puede dejar al azar.

## Registro del sistema

**Verificado**: `persist.log.tag=I`. El sistema descarta toda línea por debajo de INFO de
cualquier app, aunque sea depurable. Cualquier diagnóstico que deba funcionar en dispositivos
de usuarios tiene que emitirse a nivel INFO o superior, o por otro canal.

## Compositor

**Verificado**: el compositor emite `Compositor probably missed frame by N ms` en casi todos
los frames (177 en ~3.2 s; mediana 5.7 ms, rango 3.8–13.2 ms).

**Hipótesis**: es un error de fase en la estimación del vsync y no frames realmente perdidos.
El aviso compara el instante en que retorna la adquisición de la siguiente imagen con el
instante de presentación deseado (`comp_renderer.c`, `renderer_wait_for_present`). Prueba
pendiente: histograma present-to-present de SurfaceFlinger para la capa `SurfaceView[Monado]`.

## Otros

- Sin calibración de visor guardada se usa la distorsión de Cardboard V1 por defecto.
- El driver Vulkan de Mali rechaza varios formatos de imagen externa al sondear
  (`VK_ERROR_FORMAT_NOT_SUPPORTED`); el arranque continúa con los que sí soporta.
