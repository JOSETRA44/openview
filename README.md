# OpenView

Plataforma de computación espacial abierta. Arranca en smartphones (visor tipo Cardboard) y
laptops, y está pensada para llegar a visores con hardware propio sin reescribir el sistema.

OpenView **no es un runtime OpenXR nuevo**: es un runtime derivado de
[Monado](https://monado.freedesktop.org) que añade lo que falta en el ecosistema abierto
(seguimiento 6DoF en hardware común, capa de producto, ruta a hardware propio).
El porqué está en [docs/adr/0001](docs/adr/0001-runtime-derivado-de-monado.md).

## Estado

Fases 0–2 en curso (línea base, runtime y caracterización de dispositivo). El detalle,
elemento a elemento, está en
[docs/findings/estado-de-verificacion.md](docs/findings/estado-de-verificacion.md) y el
plan en [ROADMAP.md](ROADMAP.md). Resumen verificado a 2026-11-12:

- CI compila el runtime en Linux y el APK de Android, corre los tests y un smoke test
  OpenXR completo (loader → IPC → servicio).
- En un teléfono real (Infinix X6885, 60 Hz) OpenView sirve a `hello_xr` de forma
  continua; con el enganche a vsync de Choreographer los frames descartados bajan de
  ~6 % a 0,2 % y la latencia de presentación queda estable en 29–32 ms.
- El seguimiento en teléfono es 3DoF por IMU. El 6DoF está bloqueado en una decisión
  abierta ([docs/adr/0003](docs/adr/0003-seguimiento-6dof-con-una-camara.md)): Basalt
  funciona con dos cámaras (error de 0,4–1,9 cm en secuencias públicas) pero rechaza
  una sola, y el backend monocular aún no está elegido.
- Todavía no hay capa de producto (home, passthrough, manos, boundary): es trabajo de
  las fases siguientes, no algo que este repositorio ya tenga.

## Estructura

| Ruta | Contenido |
|---|---|
| `runtime/` | Biblioteca OpenXR (`openxr_openview`) y servicio (`openview-service`) |
| `third_party/monado/` | Submódulo fijado; se consume con `add_subdirectory()` |
| `patches/monado/` | Cambios sobre Monado pendientes de enviar upstream |
| `android/` | APK del runtime (`dev.openview.runtime`) sobre `android_common` de Monado |
| `tools/sensor-recorder/` | App y análisis para capturar cámara + IMU y exportar en formato EuRoC |
| `tools/bench/` | Evaluación de backends de seguimiento contra secuencias con referencia |
| `docs/architecture/` | Capas y regla de dependencias |
| `docs/adr/` | Decisiones de arquitectura, una por archivo |
| `docs/findings/` | Hallazgos de investigación, clasificados por nivel de evidencia |
| `ROADMAP.md` | Fases del proyecto y qué falta para acercarse a un visor completo |

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

## Compilar (Android)

```sh
git submodule update --init
git -C third_party/monado apply "$PWD"/patches/monado/*.patch
./gradlew --no-daemon :app:assembleDebug :recorder:assembleDebug
```

Los APK de depuración se firman con `android/debug.keystore`, una clave de depuración
fija y pública a propósito (para que los artefactos de CI sean instalables uno sobre
otro). No se usa para ninguna publicación.

## Licencia

BSL-1.0, la misma que Monado, para poder enviar cambios upstream sin fricción.
