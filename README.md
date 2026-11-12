# OpenView

Plataforma de computación espacial abierta. Arranca en smartphones (visor tipo Cardboard) y
laptops, y está pensada para llegar a visores con hardware propio sin reescribir el sistema.

OpenView **no es un runtime OpenXR nuevo**: es un runtime derivado de
[Monado](https://monado.freedesktop.org) que añade lo que falta en el ecosistema abierto
(seguimiento 6DoF en hardware común, capa de producto, ruta a hardware propio).
El porqué está en [docs/adr/0001](docs/adr/0001-runtime-derivado-de-monado.md).

## Estado

Fase 0–1 (línea base y esqueleto). Nada de lo que hay aquí se ha compilado ni ejecutado todavía;
ver [docs/findings/estado-de-verificacion.md](docs/findings/estado-de-verificacion.md).

## Estructura

| Ruta | Contenido |
|---|---|
| `runtime/` | Biblioteca OpenXR (`openxr_openview`) y servicio (`openview-service`) |
| `third_party/monado/` | Submódulo fijado; se consume con `add_subdirectory()` |
| `patches/monado/` | Cambios sobre Monado pendientes de enviar upstream |
| `docs/architecture/` | Capas y regla de dependencias |
| `docs/adr/` | Decisiones de arquitectura, una por archivo |
| `docs/findings/` | Hallazgos de investigación, clasificados por nivel de evidencia |

## Compilar (Linux)

```sh
git submodule update --init
git -C third_party/monado apply "$PWD"/patches/monado/*.patch
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build
```

Dependencias en Debian/Ubuntu: `build-essential cmake ninja-build pkg-config libeigen3-dev
libvulkan-dev glslang-tools libsdl2-dev libx11-xcb-dev libxcb-randr0-dev libxrandr-dev libgl-dev libopengl-dev
libegl-dev libudev-dev libbsd-dev libcjson-dev`.

Ejecutar: `build/runtime/openview-service` y, en otra terminal, cualquier app OpenXR con
`XR_RUNTIME_JSON=build/openxr_openview-dev.json`.

## Licencia

BSL-1.0, la misma que Monado, para poder enviar cambios upstream sin fricción.
