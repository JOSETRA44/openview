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
