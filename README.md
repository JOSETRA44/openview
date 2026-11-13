# OpenView

Plataforma de computación espacial abierta. Arranca en smartphones (visor tipo Cardboard) y
laptops, y está pensada para llegar a visores con hardware propio sin reescribir el sistema.

OpenView **no es un runtime OpenXR nuevo**: es un runtime derivado de
[Monado](https://monado.freedesktop.org) que añade lo que falta en el ecosistema abierto
(seguimiento 6DoF en hardware común, capa de producto, ruta a hardware propio).
El porqué está en [docs/adr/0001](docs/adr/0001-runtime-derivado-de-monado.md).

## Estado

Runtime derivado de Monado compilado en Linux y Android, con seguimiento de orientación
(3DoF) probado en un Infinix X6885. El seguimiento posicional 6DoF del teléfono sigue en
desarrollo; los resultados estéreo de Basalt no equivalen a 6DoF en el teléfono.
Ver [estado de verificación](docs/findings/estado-de-verificacion.md).

La experiencia propia se implementa en `android/experience`: cliente OpenXR con entorno
estéreo y cámara en directo con objetos virtuales. OpenView ofrece la entrada desde su
pantalla principal. El build Android publica dos APKs de producto: `openview-debug-apk`
(runtime y entrada) y `openview-experience-debug-apk` (cliente espacial). Se instalan ambos.
La cámara es un fondo monocular, sin anclajes a superficies ni seguimiento de traslación.
El estado de las pruebas se mantiene en [el registro de integración](docs/findings/experiencia-integracion.md).

## Estructura

| Ruta | Contenido |
|---|---|
| `runtime/` | Biblioteca OpenXR (`openxr_openview`) y servicio (`openview-service`) |
| `android/app/` | Entrada OpenView, ajustes del visor y servicio Android |
| `android/experience/` | Experiencia espacial propia, cliente OpenXR independiente |
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
