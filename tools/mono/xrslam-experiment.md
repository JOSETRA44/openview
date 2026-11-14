# Experimento monocular XRSLAM

Ejecutable experimental, separado de Monado y sin adaptador VIT. El workflow
`xrslam-eval` compila y ejecuta cam0+IMU de EuRoC V1_01_easy, con un límite total de
20 minutos y de 300 segundos para el tracker. No descarga datos en el teléfono.

## Reproducibilidad

- XRSLAM: `4beb1a942f33da9afbfae2d70e2c641cfc2bb675`.
- Eigen: `cf794d3b741a6278df169e58461f8529f43bce5d` (3.3.7).
- Ceres: `e809cf0c2879f521078b4c9e6329390b42ecf722` (pin original upstream).
- spdlog: `a7148b718ea2fabb8387cb90aee9bf448da63e65` (1.3.1).
- yaml-cpp: `0579ae3d976091d7d664aa9d2527e0d0cff25763` (0.7.0).
- OpenCV y demás paquetes de sistema: Ubuntu 22.04; versiones registradas en el
  artefacto, no fijadas a una imagen inmutable.
- Datos: réplica pública `pepijn223/euroc-mirror`, revisión
  `d05bd6a92a5f456562153f83d65805fea27cb57d`, `V1_01_easy.zip`, SHA256
  `a920fe5b5e69a6ad19b32f1cfaf90ac2f59d45dfd1ca18cc9722e07684ba45fa`.
  Se verifica contra el hash LFS publicado por la réplica, no contra un hash del
  editor original. Origen, checksum y archivos de entrada quedan identificados.

`xrslam_prepare.py` comprueba el commit y hace cambios **de build**: omite el player
gráfico y el ejecutable de relocalización, fija las dependencias y agrega nuestro
harness. No cambia la inicialización ni el algoritmo de estimación. El parche
resultante se conserva junto a los logs. El flag upstream `XRSLAM_PC_HEADLESS_ONLY`
no evita actualmente que su player cree la GUI y espere indefinidamente al final;
por eso no usamos ese player.

`xrslam_headless.cpp` es código original de OpenView sobre la API pública
`XRSLAMCreate/PushSensorData/RunOneFrame/GetResult`. Lee cam0 e IMU ordenados por
timestamp, rectifica la imagen con la calibración EuRoC del backend y escribe la
pose del **cuerpo/IMU** en TUM. No abre cam1 y falla ante imágenes faltantes o
dimensiones inesperadas. Solo acepta time_offset=0 para este experimento.

`xrslam_eval.py` convierte los segundos de TUM a nanosegundos, compara posiciones
del cuerpo contra `state_groundtruth_estimate0`, usa el evaluador existente de
OpenView y **no ajusta escala**. Exige terminación correcta, cobertura temporal
≥80 %, ATE RMSE ≤1 m y hueco máximo entre poses ≤2 s. Es una prueba funcional
inicial, no un umbral de calidad suficiente para XR. Publica métricas incluso si
el tracker no supera esos controles. Un timeout conserva trayectoria y logs, pero
no acepta un score de una ejecución incompleta.

## Ejecutar

El workflow corre al cambiar sus archivos o desde GitHub Actions. Para Linux:

```sh
python3 tools/mono/xrslam_test.py
python3 tools/mono/xrslam_prepare.py /ruta/al/clon-fijado
cmake -S /ruta/al/clon-fijado -B build-xrslam -G Ninja \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DXRSLAM_ENABLE_THREADING=OFF \
  -DXRSLAM_ENABLE_DEBUG_INSPECTION=OFF \
  -DOPENVIEW_HEADLESS_SOURCE="$PWD/tools/mono/xrslam_headless.cpp"
cmake --build build-xrslam --target openview-xrslam-headless --parallel 2
python3 tools/mono/xrslam_eval.py --binary build-xrslam/openview-xrslam-headless \
  --source /ruta/al/clon-fijado --sequence /ruta/V1_01_easy/mav0 --sequence-name V1_01_easy --output result
```

Las dos pruebas del evaluador pasan localmente: unidades temporales correctas,
alineación rígida, escala incorrecta penalizada y timestamps duplicados rechazados.
**Verificado en CI:** [corrida 43354832914](https://github.com/JOSETRA44/openview/actions/runs/43354832914)
compiló el core y nuestro harness. Falló después al descargar MH01: el servidor
legacy `robotics.ethz.ch` no respondió. El editor ahora distribuye un archivo
Machine Hall de aproximadamente 12 GB, según las
[instrucciones actualizadas de NVIDIA](https://github.com/nvidia-isaac/cuVSLAM/blob/main/examples/euroc/README.md).
Se cambió a V1_01_easy (1.15 GB) desde la réplica fijada para poder ejecutar el
experimento acotado.

## Primer resultado monocular medido

**Verificado en CI:** [corrida 43356408254](https://github.com/JOSETRA44/openview/actions/runs/43356408254),
commit OpenView `2d0bf9feeddd50525020c94598a220823afb5bec`, Ubuntu 22.04.
Build, checksum del dataset, reproducción y evaluación terminaron correctamente.
Se alimentó **una sola cámara (cam0) y el IMU**; cam1 ni siquiera se extrajo.

| Magnitud | Resultado |
|---|---|
| Secuencia | EuRoC V1_01_easy |
| ATE RMSE sin ajuste de escala | **5.18 cm** |
| ATE mediana / máximo | 4.16 / 12.28 cm |
| Recorrido evaluado | 58.53 m |
| ATE / recorrido | 0.0885 % |
| Cobertura temporal respecto a referencia | 96.97 % |
| Mayor intervalo entre poses | 50.0004 ms |
| Poses dentro del intervalo de referencia | 2785 |
| Poses emitidas / imágenes procesadas | 2805 / 2912 |
| Tiempo total de reproducción y proceso | 66.23 s |
| Escala de alineación | 1.0, fija |

La diferencia entre poses emitidas y evaluadas corresponde a los timestamps que
quedan fuera del intervalo de ground truth. Se conservaron `trajectory.tum`,
`tracker.log`, `results.json`, checksums y revisiones de dependencias en el
artefacto `xrslam-monocular-experiment`. Copia local de esta corrida:
`build/xrslam-evidence/` (ignorada por Git).

Esto demuestra que existe un candidato abierto capaz de producir una trayectoria
monocular inercial métrica en nuestro experimento. Es una sola corrida, una sola
secuencia de obturador global, en CPU de CI; no establece repetibilidad,
compensación rolling shutter, rendimiento térmico/Android ni funcionamiento
con la cámara del Infinix. No está integrado por VIT ni incluido en los APK.

## Fuentes y límites

Fuentes primarias de la revisión:
[API pública](https://github.com/openxrlab/xrslam/blob/4beb1a942f33da9afbfae2d70e2c641cfc2bb675/xrslam-interface/include/XRSLAM.h),
[conversión a pose de cuerpo](https://github.com/openxrlab/xrslam/blob/4beb1a942f33da9afbfae2d70e2c641cfc2bb675/xrslam-interface/src/XRSLAMManager.cpp),
[lector EuRoC](https://github.com/openxrlab/xrslam/blob/4beb1a942f33da9afbfae2d70e2c641cfc2bb675/xrslam-pc/player/src/IO/euroc_dataset_reader.cpp),
[configuración](https://github.com/openxrlab/xrslam/blob/4beb1a942f33da9afbfae2d70e2c641cfc2bb675/configs/euroc_sensor.yaml).

La configuración pone IMU→cuerpo en identidad y desplazamiento cero. El manager
recibe gyro xyz en rad/s y aceleración xyz en m/s² sin cambios de ejes; el harness
lee esas seis columnas de EuRoC en el mismo orden. La salida BODY_POSE conserva
la posición IMU, mientras CAMERA_POSE añadiría el brazo de palanca: por eso se
compara BODY_POSE con ground truth del cuerpo. El marco global arbitrario se
resuelve mediante alineación rígida, no escala.

XRSLAM declara Apache-2.0 para su código. Las dependencias tienen licencias
independientes; se conservan los archivos LICENSE/COPYING de los paquetes
descargados como evidencia, sin afirmar una auditoría completa de dependencias
transitivas. No se incorpora XRSLAM a los APK ni al runtime. EuRoC usa cámaras
global shutter calibradas: pasar esta prueba no demuestra compensación rolling
shutter, rendimiento Android ni aptitud para el Infinix.
