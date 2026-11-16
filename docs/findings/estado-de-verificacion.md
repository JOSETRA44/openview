# Estado de verificación

Actualizado: 2026-11-12. La verificación se hace en GitHub Actions (`.github/workflows/build.yml`).

| Elemento | Estado |
|---|---|
| Submódulo Monado fijado a commit upstream (`v25.1.0-1107-ga32ea353`) | Verificado: CI lo clona |
| `patches/monado/0001` aplica sobre ese commit | Verificado en CI |
| Build Linux de `libopenxr_openview.so` y `openview-service` | Verificado en CI (Ubuntu 24.04, GCC) |
| Tests de Monado (`ctest`) con nuestra selección de módulos | Verificado en CI |
| Cliente OpenXR → runtime → IPC → servicio | Verificado en CI: `openxr_runtime_list` crea la instancia (`xrCreateInstance`), obtiene el sistema (`xrGetSystem`) y el servicio registra `Client 1 connected`. Vulkan por software (lavapipe) sobre Xvfb |
| Renderizado de frames (`hello_xr`) en Linux CI | No ejecutado; las pruebas Android se registran por separado |
| El parche 0001 compila | Verificado en CI para arm64-v8a, armeabi-v7a, x86 y x86_64: `monado-service` de Android enlaza `drv_android` y el build termina sin errores. Su comportamiento en ejecución **no** está probado |
| APK propio de OpenView (`dev.openview.runtime`, arm64-v8a y x86_64) | Verificado en CI; artefacto `openview-debug-apk`, firmado con una clave de depuración fija |
| OpenView sirve a `hello_xr` en un teléfono de forma continua | Verificado en un Infinix X6885 (corridas de 60 y 90 s), con el permiso de superposición concedido. Ver [dispositivo-infinix-x6885.md](dispositivo-infinix-x6885.md) |
| Aviso cuando falta el permiso de superposición | Verificado en ese dispositivo |
| Predicción de pose: conversión de reloj y límite | Verificado en ese dispositivo: 0 de 6060 consultas recortadas |
| Modelo de latencia de pantalla (parche 0002) | Verificado en ese dispositivo a 60 y 90 Hz (1.7–1.9 periodos medidos); 120/144 Hz sin medir: el teléfono no los concede |
| El runtime conoce la tasa de refresco real | **Defecto**: la lee al arrancar y no pide ninguna; medido un desajuste (supone 90 Hz, panel a 60). Ver [dispositivo-infinix-x6885.md](dispositivo-infinix-x6885.md) |
| Temporización con la app propia `experience` | Verificado a 60 Hz, espacio y cámara: 0.1–1.8 % descartados, 28–29 ms |
| Inicio de `experience` con paneles flotantes | Verificado que se dibuja en el Infinix X6885 (lanzador, apps OpenXR instaladas, hora y batería) a 60 fps. **Sin verificar**: elegir un botón con la mirada, que exige mover el teléfono |
| 6DoF en el teléfono (Basalt monocular en el runtime) | **No funciona todavía**, apagado por defecto. Verificado: el runtime arranca con el tracker enganchado y la cámara le llega a 30 fps. Defecto abierto: el IMU deja de llegar a los 5 s porque Basalt bloquea al hilo que se lo entrega (cola de 300 muestras llena) y no sale ninguna pose. Ver issue #16 |
| Compositor enganchado al vsync (parche 0003) | Verificado en ese dispositivo a 60 Hz: 0.2 % de frames descartados, latencia 29–32 ms |
| Grabadora de sensores (`tools/sensor-recorder`) | Verificada en ese dispositivo: cámara, IMU y reporte del dispositivo |
| `analyze.py`: estadísticas y exportación EuRoC | Verificado con una captura real; estimadores probados con señales sintéticas |
| Desfase cámara–IMU | Estimación preliminar en una captura con giros (145427); falta repetir con exposición fija. Ver informe del dispositivo |
| Seguimiento 6DoF con Basalt y dos cámaras, en nuestro build | Verificado en CI con 4 secuencias públicas: 0.4–1.9 cm de error, 1.5–2.1× tiempo real. Ver [vio-evaluacion.md](vio-evaluacion.md) |
| Basalt con una cámara | Tal como se distribuye, verificado que **no** arranca (aserción). Con el parche de OpenView, verificado en CI: 0,9–2,0 cm, 99–100 % de cobertura y 3,0–4,9× tiempo real en las 4 secuencias públicas, sin ajustar escala. Ver [vio-evaluacion.md](vio-evaluacion.md) |
| OKVIS2 con una cámara | Verificado en CI sin parchear: 4,53 cm de ATE, 99,96 % de cobertura, 1,6× tiempo real en EuRoC V1_01_easy, sin ajustar escala. Ver [vio-evaluacion.md](vio-evaluacion.md) |
| ADR-0003 | **Decidida (2026-11-14)**: Basalt parcheado como motor del teléfono, OKVIS2 como respaldo. Ver [0003-seguimiento-6dof-con-una-camara.md](../adr/0003-seguimiento-6dof-con-una-camara.md) |
| Backend de seguimiento para una cámara | Sin elegir, ver [ADR-0003](../adr/0003-seguimiento-6dof-con-una-camara.md) |
| Calibración de la cámara del teléfono | No hecha |
| Herramienta de calibración intrínseca | Implementada; 8 pruebas locales y CI `mono-tools` verificadas. Falta captura del tablero y calibración real |
| Experiencia propia OpenView | Build CI e instalación verificados; entrada propia, escena estéreo, cámara con objetos y ciclo de vida probados en Infinix. Ver [experiencia-integracion.md](experiencia-integracion.md) |
| XRSLAM monocular + IMU | Ejecutado offline en CI: V1_01_easy, ATE RMSE 5.18 cm, cobertura 96.97 %, sin ajustar escala. Una corrida; sin integración Android/VIT. Ver [experimento](../../tools/mono/xrslam-experiment.md) |
| Evaluar una captura propia de teléfono | No ejecutado: falta calibración y backend |
| Trayectoria de referencia para capturas propias (ARCore) | No implementado |
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
