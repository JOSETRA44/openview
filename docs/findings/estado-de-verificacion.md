# Estado de verificación

Actualizado: 2026-11-12. La verificación se hace en GitHub Actions (`.github/workflows/build.yml`).

| Elemento | Estado |
|---|---|
| Submódulo Monado fijado a commit upstream (`v25.1.0-1107-ga32ea353`) | Verificado: CI lo clona |
| `patches/monado/0001` aplica sobre ese commit | Verificado en CI |
| Build Linux de `libopenxr_openview.so` y `openview-service` | Verificado en CI (Ubuntu 24.04, GCC) |
| Tests de Monado (`ctest`) con nuestra selección de módulos | Verificado en CI |
| Cliente OpenXR → runtime → IPC → servicio | Verificado en CI: `openxr_runtime_list` crea la instancia (`xrCreateInstance`), obtiene el sistema (`xrGetSystem`) y el servicio registra `Client 1 connected`. Vulkan por software (lavapipe) sobre Xvfb |
| Renderizado de frames (`hello_xr`) | No ejecutado |
| El parche 0001 compila | Verificado en CI para arm64-v8a, armeabi-v7a, x86 y x86_64: `monado-service` de Android enlaza `drv_android` y el build termina sin errores. Su comportamiento en ejecución **no** está probado |
| APK propio de OpenView (`dev.openview.runtime`, arm64-v8a y x86_64) | Verificado en CI; artefacto `openview-debug-apk`, firmado con una clave de depuración fija |
| OpenView sirve a `hello_xr` en un teléfono de forma continua | Verificado en un Infinix X6885 (corridas de 60 y 90 s), con el permiso de superposición concedido. Ver [dispositivo-infinix-x6885.md](dispositivo-infinix-x6885.md) |
| Aviso cuando falta el permiso de superposición | Verificado en ese dispositivo |
| Predicción de pose: conversión de reloj y límite | Verificado en ese dispositivo: 0 de 6060 consultas recortadas |
| Modelo de latencia de pantalla (parche 0002) | Verificado en ese dispositivo a 60 Hz; sin probar a 90/120/144 Hz |
| Compositor enganchado al vsync (parche 0003) | Verificado en ese dispositivo a 60 Hz: 0.2 % de frames descartados, latencia 29–32 ms |
| Grabadora de sensores (`tools/sensor-recorder`) | Verificada en ese dispositivo: cámara, IMU y reporte del dispositivo |
| `analyze.py`: estadísticas y exportación EuRoC | Verificado con una captura real; estimadores probados con señales sintéticas |
| Desfase cámara–IMU | No medido: falta una captura con giros |
| Reproducir un dataset propio en Monado (`drivers/euroc`) | No ejecutado |
| Trayectoria de referencia (ARCore) | No implementado |
| Efecto visual de la predicción, error angular, latencia percibida | No medido |
| Térmica con una aplicación exigente | No medido; solo `hello_xr` |
| Otros teléfonos y fabricantes | Ninguno probado |
| Una app que no sea `hello_xr` (Godot, StereoKit) | No probado |

## Hallazgos del primer build

- `FindOpenGL` solo crea `OpenGL::GLX` si existe la `libOpenGL.so` de GLVND. Sin `libopengl-dev`
  la configuración termina bien y falla al generar. Monado no lo detecta por sí mismo.
- `monado_set_options()` apaga `XRT_MODULE_COMPOSITOR_MOCK`, pero los tests de Monado lo
  enlazan siempre que hay Vulkan: con `BUILD_TESTING=ON` hay que mantenerlo.
- `drv_remote` enlaza `aux_vive` sin que `XRT_BUILD_DRIVER_REMOTE` lo declare como dependencia.
  Candidato a corrección upstream.
- Un commit hecho dentro del submódulo deja al superproyecto apuntando a un objeto que solo
  existe en ese clon. Los cambios sobre Monado van siempre como archivos en `patches/monado/`.

## Entorno local

El disco del sistema de la máquina de desarrollo es pequeño: los builds se hacen en CI y los
artefactos y capturas se guardan en una unidad externa.
