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
| `0001-d-android-predict-head-pose` | El driver Android guarda las orientaciones en un `m_relation_history` y responde a `get_tracked_pose` para el instante pedido, en vez de ignorarlo. Convierte los timestamps de sensor de `CLOCK_BOOTTIME` a `CLOCK_MONOTONIC` | Sin enviar; falta probar en dispositivo y definir el límite de predicción |
