# Android como plataforma XR

Fuente: developer.android.com y la especificación del loader de OpenXR, leídas el 2026-11-11.
**Verificado** en documentación salvo que se indique; nada medido en dispositivo todavía.

## Descubrimiento del runtime

El loader pregunta por ContentProvider, en este orden: broker instalable
(`org.khronos.openxr.runtime_broker`), broker de sistema
(`org.khronos.openxr.system_runtime_broker`), y por último
`/{product,odm,oem,vendor,system}/etc/openxr/<major>/active_runtime[.<abi>].json`.

Un runtime instalable exporta un servicio con la acción
`org.khronos.openxr.OpenXRRuntimeService` y los metadatos `SoFilename` y `MajorVersion`.

Consecuencia: el mismo runtime es un APK en un teléfono cualquiera y el runtime de sistema en
un dispositivo propio. Cambia el empaquetado, no el código.

## Sensores

- Apps con target API 31+: `registerListener` limitado a 200 Hz y `SensorDirectChannel` a
  ~50 Hz, salvo que declaren `HIGH_SAMPLING_RATE_SENSORS`.
- `SensorDirectChannel`: `RATE_FAST` 200 Hz y `RATE_VERY_FAST` 800 Hz nominales (real entre
  55 % y 220 %). Disponibilidad por dispositivo no documentada.
- El CDD solo obliga a 100 Hz en acelerómetro y giroscopio. **Límite**: no se puede asumir más.
- Con el micrófono desactivado desde el interruptor del sistema, los sensores de movimiento
  quedan limitados aunque se tenga el permiso.

## Cámara

- `SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME`: timestamps comparables con el IMU.
  `UNKNOWN`: no comparables. Depende del dispositivo. **Límite**: el offset cámara–IMU hay que
  estimarlo, no suponerlo.
- Dos cámaras a la vez solo está garantizado dentro de una cámara lógica, y la documentación
  advierte que "requires performing per-device testing". **Límite**: T1 debe funcionar en monocular.
- Rolling shutter en prácticamente todos los teléfonos (**Hipótesis** razonable, a caracterizar
  por dispositivo en Fase 2).

## Pantalla y latencia

- Una app normal presenta a través de SurfaceFlinger. El modo VR del CDD
  (`android.hardware.vr.high_performance`, `VrListenerService`) es una característica del
  dispositivo, no algo que la app pueda exigir.
- **Hipótesis**: predicción de pose + reproyección del compositor dan una latencia percibida
  aceptable sin front-buffer. Prueba: Fase 0 y 1b.
- En hardware propio sí se controla el camino de presentación.

## Térmica y rendimiento

`AThermal_getThermalHeadroom` (API 31; 1.0 = throttling severo, con pronóstico a N segundos),
estados térmicos y listeners (API 30), ADPF. Suficiente para un gobernador que degrade
resolución, refresh o tracking antes del throttling.
