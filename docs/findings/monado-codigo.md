# Monado: lectura del código

Commit `a32ea353` (2026-11-10, `v25.1.0-1107`). Rutas relativas a `third_party/monado/`.
Todo lo de esta página es **Verificado** por lectura salvo que se indique.

## Extensibilidad

- `examples/derived_runtime/`: proyecto externo que hace `add_subdirectory()` de Monado.
  `monado_set_options()` apaga toda opción no listada; `monado_add_openxr_runtime()` genera
  la biblioteca y su manifiesto.
- `settings.gradle`: `:src:xrt:targets:android_common` es la base "required by all
  Monado-based runtimes on AOSP platforms. Use dependency injection to customize" (Hilt).
  `openxr_android` es el APK hoja de ejemplo.
- `monado-ctl` y `libmonado` solo conectan al socket por defecto: no alcanzan a un servicio
  derivado (`doc/understanding-targets.md`). **Límite** para herramientas de diagnóstico.

## Android

- Fuera de proceso: `src/xrt/ipc/android/` (`IMonado.aidl`, `MonadoService.kt`,
  `SurfaceManager.kt`). Sabores Gradle `inProcess` y `outOfProcess`.
- Build fijado a NDK 26.3.11579264, CMake 3.22.1, minSdk 26, targetSdk 31, compileSdk 35.
  `versionCode` sale de `git describe --match 'v*'`: requiere historial y tags.
- `HIGH_SAMPLING_RATE_SENSORS` ya está declarado en el manifest de `openxr_android`.

## Driver del teléfono (`src/xrt/drivers/android/android_sensors.c`)

- Fusión 3DoF con `m_imu_3dof`; `supported.position_tracking = false`.
- `android_device_get_tracked_pose` descarta `at_timestamp_ns` y devuelve la última
  orientación fusionada: **no hay predicción de pose**. `m_relation_history` y `m_predict`
  existen y otros drivers 3DoF (p. ej. `xreal_air`) ya los usan.
- Tasa de sensores pedida: 3× el refresh de pantalla (≈180 Hz a 60 Hz).
- `m_predict_relation` extrapola sin límite de horizonte.
- No hay ninguna conversión entre `CLOCK_BOOTTIME` y `CLOCK_MONOTONIC` en todo el árbol.
  `os_monotonic_get_ns()` usa `CLOCK_MONOTONIC`. **Hipótesis**: los timestamps de
  `ASensorEvent` están en base `CLOCK_BOOTTIME` (así lo documenta Android); comprobar en
  dispositivo comparando ambos relojes tras una suspensión.

## Tracking visual-inercial

- Interfaz VIT 2.0.1 en `src/external/vit_includes/vit/vit_interface.h` (BSL-1.0). El backend
  es una biblioteca compartida cargada con `dlopen` (`auxiliary/tracking/t_vit_loader.c`);
  por defecto `libbasalt.so`, configurable con `VIT_SYSTEM_LIBRARY_PATH`.
- `vit_config.cam_count` es configurable (`SLAM_CAM_COUNT`, por defecto 2).
- Calibración de cámara: pinhole + RT4/RT5/RT8/KB4 y `T_imu_cam`. **No hay campos** para
  tiempo de lectura de rolling shutter, offset temporal cámara–IMU ni exposición. **Límite**
  para cámaras de teléfono; solo queda `vit_config.file` como vía específica del backend.
- `XRT_FEATURE_SLAM` depende de `XRT_HAVE_OPENCV` y `XRT_HAVE_LINUX` (`CMakeLists.txt:311`);
  `XRT_HAVE_LINUX` solo se activa con `CMAKE_SYSTEM_NAME == Linux`. En Android está apagado.
- Implementaciones de `xrt_fs` (cámara): v4l2, uvc, depthai, realsense, euroc, rift_s, wmr.
  Ninguna para Android.
- Datasets: `auxiliary/tracking/t_euroc_recorder.cpp` y `drivers/euroc/` (reproductor que
  alimenta al tracker SLAM). `XRT_BUILD_DRIVER_EUROC` excluye MSVC.

## Escritorio

- Windows: "work-in-progress… not ready for widespread usage due to some rough edges and
  lack of drivers" (`doc/winbuild.md`). MSVC + vcpkg + Vulkan SDK.
- Compositor: reproyección en `auxiliary/render/` (rutas gráfica y de cómputo), pacing en
  `auxiliary/util/u_pacing_*.c`, ventana Android en `compositor/main/comp_window_android.c`.
- Tests con Catch2 en `tests/`; CI en `.gitlab-ci/`.
