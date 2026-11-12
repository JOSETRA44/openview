# Estado de verificación

Actualizado: 2026-11-11. La verificación se hace en GitHub Actions (`.github/workflows/build.yml`).

| Elemento | Estado |
|---|---|
| Submódulo Monado fijado a commit upstream (`v25.1.0-1107-ga32ea353`) | Verificado: CI lo clona |
| `patches/monado/0001` aplica sobre ese commit | Verificado en CI |
| Build Linux de `libopenxr_openview.so` y `openview-service` | Verificado en CI (Ubuntu 24.04, GCC) |
| Tests de Monado (`ctest`) con nuestra selección de módulos | Verificado en CI |
| Cliente OpenXR → runtime → IPC → servicio | Verificado en CI: `openxr_runtime_list` crea la instancia (`xrCreateInstance`), obtiene el sistema (`xrGetSystem`) y el servicio registra `Client 1 connected`. Vulkan por software (lavapipe) sobre Xvfb |
| Renderizado de frames (`hello_xr`) | No ejecutado |
| El parche 0001 compila | **No verificado**: el driver Android no se compila en el build Linux |
| APK Android | En curso en CI (job `android`) |
| `hello_xr` en teléfono, frame time, térmica, efecto de la predicción | No ejecutado: requiere dispositivo |

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

La máquina de desarrollo actual tiene el disco del sistema casi lleno y no hay dispositivo
Android conectado, así que por ahora no se compila en local.
