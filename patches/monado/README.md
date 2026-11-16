# Parches sobre Monado

Cambios que OpenView necesita y que aún no están upstream. Se aplican sobre el commit fijado
del submódulo:

```sh
git -C third_party/monado apply "$PWD"/patches/monado/*.patch
```

Son archivos y no una rama del submódulo porque así el repo es reproducible sin depender de
un fork alojado. Cuando un parche se acepta upstream y se sube el commit fijado, se borra.

| Parche | Qué hace | Upstream |
|---|---|---|
| `0001-d-android-predict-head-pose` | El driver Android guarda las orientaciones en un `m_relation_history` y responde a `get_tracked_pose` para el instante pedido, en vez de ignorarlo. Convierte los timestamps de sensor de `CLOCK_BOOTTIME` a `CLOCK_MONOTONIC`. Limita la extrapolación a 5 intervalos de frame (40–100 ms). Con nivel de log `debug` emite un resumen de tiempos por segundo | Sin enviar; validado en un dispositivo |
| `0002-u-pacing-android-present-to-display-offset` | En Android el pacer del compositor asume 2 periodos de frame entre presentar y mostrar (SurfaceFlinger), en vez de los 4 ms pensados para escritorio. `U_PACING_COMP_PRESENT_TO_DISPLAY_OFFSET_MS` sigue pudiendo forzarlo | Sin enviar; es un modelo fijo, la solución real es sincronizar con Choreographer |
| `0003-c-main-android-choreographer-vsync` | Un hilo recibe el vsync de `AChoreographer` y se lo pasa al pacer por el mismo camino que usa el modo directo de escritorio, para que el compositor quede enganchado en fase. Solo ABIs de 64 bits. `XRT_COMPOSITOR_ANDROID_VSYNC_OFFSET_MS` desplaza la fase para ajustarla | Sin enviar; en validación |
| `0004-d-android-slam-tracker-poses` | El dispositivo Android pide un tracker SLAM a la factoría del prober. La orientación sigue saliendo de la fusión del giroscopio; el tracker da la posición y corrige el rumbo despacio. Su mundo se alinea con el de salida cada vez que empieza a seguir, así que empezar, perder o recuperar el seguimiento no hace saltar la vista. Un tracker parado o con saltos imposibles deja de usarse y la posición se mantiene | Sin enviar. El arranque en 3DoF está validado en un dispositivo; el 6DoF no |
| `0006-d-android-camera-slam-frameserver` | Frameserver que da al tracker la cámara trasera (NDK Camera2, 640×480 a 30 fps, sin estabilización, foco al infinito) y el IMU, y la rama Android de la factoría de tracking. Comprueba que la configuración y la calibración existen antes de crear el tracker. Los frames van en formato L8: el tracker SLAM descarta cualquier otro formato de un canal («Unknown image format»), que era la causa de que Basalt no recibiera imágenes ni entregara poses. Encendido por defecto | Sin enviar. Causa raíz del IMU bloqueado identificada en código (investigación de Codex, verificada por Poly); pendiente la prueba en dispositivo |
| `0007-xrt-enable-slam-on-android` | Permite compilar el tracker SLAM en Android, donde Monado lo excluía | Sin enviar |
