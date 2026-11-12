# ADR-0001: OpenView es un runtime derivado de Monado

Estado: aceptada (2026-11-11). Pendiente de confirmar con la línea base de Fase 0.

## Contexto

El objetivo inicial era "un Meta Quest multiplataforma", con la opción de construir las
dependencias desde cero. Un runtime OpenXR conforme incluye state tracker, compositor con
distorsión y reproyección, IPC multiproceso, integración con el ciclo de vida de Android y
decenas de extensiones. Nada de eso diferencia al producto.

## Opciones consideradas

| Opción | Resultado |
|---|---|
| Runtime propio desde cero | Años hasta conformidad; ningún valor diferencial |
| Google Cardboard SDK | Apache-2.0 y mantenido, pero es una biblioteca por app, sin OpenXR ni proceso de sistema |
| OpenHMD | Declarado no mantenido por sus autores; remite a Monado |
| PhoneXR / FullyMobileVR | Sin licencia / AGPL con 4 commits; ambos dependen de ARCore |
| Fork de Monado | Funciona, pero obliga a arrastrar un delta creciente |
| **Runtime derivado de Monado** | Elegida |

## Decisión

Consumir Monado como submódulo con `add_subdirectory()`, siguiendo el patrón que Monado
mantiene para esto (`examples/derived_runtime/`, `cmake/MonadoSetOptions.cmake`,
`cmake/MonadoOpenXRRuntime.cmake`; en Android, el módulo Gradle `android_common`).

- OpenView define su lista de módulos y drivers, sus puntos de entrada y sus nombres de IPC.
- Las interfaces `xrt_*` y VIT son los puertos; no se añade abstracción propia.
- Lo genérico se envía upstream; lo pendiente vive en `patches/monado/`.
- Licencia BSL-1.0, igual que Monado.

## Evidencia

- Monado es BSL-1.0 (1456 archivos con ese SPDX en `src/`; el resto Apache-2.0, BSD, MIT).
- Corre fuera de proceso en Android (`src/xrt/ipc/android/`) y ya tiene driver Cardboard.
- `doc/understanding-targets.md` lo describe como "runtime construction kit" y documenta
  los runtimes derivados.
- Detalle en [../findings/monado-codigo.md](../findings/monado-codigo.md).

## Consecuencias

- Heredamos el ritmo y las decisiones de Monado. El submódulo se fija por commit para que
  subir de versión sea un acto deliberado.
- El commit fijado (`a32ea353`, 1107 commits después de `v25.1.0`) es de la rama principal,
  no de una versión estable: el soporte de runtimes derivados aún no está en ningún tag.
  Hay que volver a fijar a un tag cuando salga uno que lo incluya.
- El esfuerzo propio se concentra en tracking 6DoF abierto, capa de producto y hardware.

## Qué la revertiría

Que la línea base de Fase 0 muestre que el compositor o el IPC de Monado no alcanzan una
latencia utilizable en teléfonos y que el problema no sea corregible upstream.
