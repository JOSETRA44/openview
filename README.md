# OpenView

Plataforma de computación espacial abierta. Arranca en smartphones (visor tipo Cardboard) y
laptops, y está pensada para llegar a visores con hardware propio sin reescribir el sistema.

OpenView **no es un runtime OpenXR nuevo**: es un runtime derivado de
[Monado](https://monado.freedesktop.org) que añade lo que falta en el ecosistema abierto
(seguimiento 6DoF en hardware común, capa de producto, ruta a hardware propio).
El porqué está en [docs/adr/0001](docs/adr/0001-runtime-derivado-de-monado.md).

## Estado

El detalle, elemento a elemento, está en
[docs/findings/estado-de-verificacion.md](docs/findings/estado-de-verificacion.md) y el
plan en [ROADMAP.md](ROADMAP.md). Resumen verificado a 2026-11-14:

- CI compila el runtime en Linux y el APK de Android, corre los tests y un smoke test
  OpenXR completo (loader → IPC → servicio).
- En un teléfono real (Infinix X6885, 60 Hz) OpenView sirve aplicaciones OpenXR de forma
  continua; con el enganche a vsync de Choreographer los frames descartados bajan de
  ~6 % a 0,2 % y la latencia de presentación queda estable en 29–32 ms.
- El seguimiento en el teléfono sigue siendo 3DoF por IMU, pero el backend 6DoF ya está
  **decidido y medido fuera del teléfono**
  ([docs/adr/0003](docs/adr/0003-seguimiento-6dof-con-una-camara.md)): Basalt parcheado a
  una cámara mide 0,88–1,97 cm de error en secuencias públicas; OKVIS2 (4,53 cm) queda
  como respaldo y XRSLAM (5,18 cm) como candidato observado. Falta correr el elegido
  sobre capturas reales del teléfono.
- Existe una primera aplicación propia, `android/experience`: cliente OpenXR
  independiente con escena estéreo y modo cámara con objetos virtuales, verificado en el
  Infinix ([registro de integración](docs/findings/experiencia-integracion.md)). El build
  Android publica dos APK: el runtime con su entrada y la experiencia. La cámara es un
  fondo monocular, sin anclajes ni seguimiento de traslación todavía.
- La capa de producto completa (home como sistema, manos, boundary) sigue siendo
  trabajo de las fases siguientes.

## Estructura

| Ruta | Contenido |
|---|---|
| `runtime/` | Biblioteca OpenXR (`openxr_openview`) y servicio (`openview-service`) |
| `android/app/` | Entrada OpenView, ajustes del visor y servicio Android |
| `android/experience/` | Experiencia espacial propia, cliente OpenXR independiente |
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
