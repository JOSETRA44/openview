# Adaptador experimental XRSLAM → VIT

Este directorio implementa la ABI **VIT 2.0.1** usada por Monado, como biblioteca
compartida independiente. No modifica Monado, no se incluye todavía en el APK y
no activa seguimiento posicional en el teléfono.

El backend real usa XRSLAM `4beb1a942f33da9afbfae2d70e2c641cfc2bb675`. Recibe una
cámara L8 y una IMU (aceleración m/s², giroscopio rad/s, timestamps ns). Copia las
imágenes del productor antes de devolver el control. Un hilo propio procesa las
muestras; el hilo de captura no ejecuta el optimizador. Una imagen espera a que
la IMU alcance su timestamp; las muestras se entregan en orden temporal. No se
inventa una pose mientras inicializa o cuando XRSLAM no declara tracking válido.

Las colas están limitadas a 16 imágenes, 4096 muestras IMU y 64 poses. Desbordar
entradas detiene el tracker y produce error: no elimina silenciosamente medidas
inerciales para continuar. Un consumidor lento puede perder poses antiguas. El
productor debe comprobar cada resultado VIT; ante fallo, detener y reinicializar.
Las operaciones de control (start/stop/reset/destroy) deben serializarse y el
productor debe dejar de enviar muestras antes de destroy. Se usa un mutex breve
para las colas; esto evita esperar al optimizador, pero no promete tiempo real
duro ni ausencia absoluta de contención del planificador.

## Configuración real, no valores por defecto inventados

`vit_config.file` apunta a un YAML OpenCV:

```yaml
%YAML:1.0
slam_config: /ruta/upstream/configs/euroc_slam.yaml
device_config: /ruta/upstream/configs/euroc_sensor.yaml
```

Las rutas relativas se resuelven respecto a ese archivo. El archivo sensor debe
contener intrínsecos, distorsión, extrínsecos e IMU completos. Se comprueban modelo
pinhole/radtan, resolución y arrays esenciales; XRSLAM interpreta el resto. Esta
versión experimental **rechaza readout distinto de cero y desfase distinto de
cero**. No convertir la calibración parcial del Infinix en este YAML agregando
identidades o ceros: es un teléfono rolling shutter y todavía faltan extrínsecos
y sincronización medidos. Los defaults de EuRoC no son calibración del teléfono.

Se rectifica cada imagen con el mismo modelo usado por el experimento headless.
Se exige que el cuerpo de salida sea la IMU (`q_bi` identidad, `p_bi` cero). La
pose se conserva en el mundo de XRSLAM, metros, cuaternión xyzw, como transformación
de IMU a mundo. La implementación fijada de `GetResultBodyPose` confirma esta
convención aunque el comentario del encabezado público resulte ambiguo. No se
aplica todavía una transformación a coordenadas de OpenXR: corresponde integrarla
y verificarla en el consumidor Monado, incluida la extrínseca IMU–pantalla.

La API C de XRSLAM no expone velocidad. VIT recibe una derivada hacia atrás de
las posiciones; se omite la primera pose y se reinicia esa derivada tras pérdida
de tracking. No es la velocidad interna del estimador y no está validada para
predicción de visor. Se restablece un origen temporal local antes de convertir a
segundos para evitar pérdida de precisión de timestamps absolutos grandes.

## Vida de la biblioteca

El C API upstream usa un singleton global; se rechaza un segundo tracker vivo.
En esta revisión, `XRSLAMManager::Destroy()` solo imprime un mensaje. Por ello el
backend no promete liberar la memoria del estimador al detenerlo: una siguiente
inicialización reemplaza su estado y la salida del proceso libera el resto. En
Linux/Android se enlaza con `-z nodelete` para no descargar código mientras haya
objetos globales vivos. Resolver y probar esa vida útil es requisito antes de
adoptarlo en el runtime. No se agregaron parches de lógica a XRSLAM.

Fuentes primarias fijadas:

- [API XRSLAM](https://github.com/openxrlab/xrslam/blob/4beb1a942f33da9afbfae2d70e2c641cfc2bb675/xrslam-interface/include/XRSLAM.h).
- [Implementación de cuerpo, singleton y Destroy](https://github.com/openxrlab/xrslam/blob/4beb1a942f33da9afbfae2d70e2c641cfc2bb675/xrslam-interface/src/XRSLAMManager.cpp).
- Contrato VIT: `third_party/monado/src/external/vit_includes/vit/vit_interface.h`.

## Verificación

Prueba local sin descargar XRSLAM:

```sh
cmake -S tracking/xrslam -B build-vit
cmake --build build-vit
ctest --test-dir build-vit --output-on-failure
```

`tests/contract.cpp` usa un backend deliberadamente sintético **solo en ese
ejecutable de pruebas**: comprueba propiedad/stride de imágenes, orden IMU,
timestamps duplicados, colas, reset, datos no finitos y excepciones. No demuestra
precisión de seguimiento. Verificado localmente en Windows con GCC, C++17,
`-Wall -Wextra -Werror`; todas las comprobaciones pasan.

El workflow separado `xrslam-vit` prepara el upstream fijado con `prepare.py`,
compila el backend real y `vit-replay`. Este consumidor llama exclusivamente la
ABI VIT, reproduce cam0+IMU de V1_01_easy a ritmo de captura y genera TUM, que se
puntúa sin ajustar escala usando el evaluador existente. También ejecuta el
contrato con AddressSanitizer y UndefinedBehaviorSanitizer. La CI debe completarse
antes de afirmar que la biblioteca real compila o que el replay pasa. Los
resultados quedan en el artefacto `xrslam-vit-experiment`, no se infieren del
resultado anterior del harness directo.

Faltan: ejecutar/revisar CI real, repetir secuencias, mejorar diagnóstico de
errores asíncronos, build ARM/Android, calibración real de cámara–IMU, tratamiento
de rolling shutter/desfase, política de pérdida/relocalización, transformación
de marcos y predicción verificadas, revisión completa de licencias transitivas,
integración con el consumidor VIT de Monado y pruebas de posición física en XR.
