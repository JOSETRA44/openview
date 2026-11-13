# Avance monocular: calibración e investigación de backend

Actualizado: 2026-11-12. Esta etapa no añade seguimiento posicional al runtime.

## Qué se implementó

`tools/mono/calibrate.py` ya genera un patrón imprimible y calibra imágenes PGM de
la grabadora. Produce intrínsecos pinhole y distorsión radial/tangencial con
controles de diversidad de vistas y validación en imágenes que no ajustaron los
intrínsecos. Rechaza capturas insuficientes y no fabrica calibraciones. Las
instrucciones operativas están en [`tools/mono/README.md`](../../tools/mono/README.md).

Se mantiene la arquitectura: es una herramienta offline, sin dependencia del
runtime ni de Android, y no coloca una API propia entre VIT y un futuro backend.

**Verificado localmente:** ocho pruebas pasan con Python 3.11.15, NumPy 2.4.6 y
OpenCV 5.0.0. Se recuperan intrínsecos conocidos desde proyecciones con distorsión
y ruido; se rechazan repeticiones, pocas vistas, ruido excesivo y puntos no
finitos; se detectan las 48 esquinas de una imagen PGM sintética; la CLI rechaza
una imagen vacía sin publicar intrínsecos. La sintaxis Python también se comprobó.

**Verificado sobre datos reales, solo rechazo:** la captura conservada
`D:/openview-artifacts/captures/20261112-150537`, muestreada cada diez imágenes,
no contiene el tablero y devuelve `rejected`, cero vistas detectadas. Esto no
calibra el teléfono ni valida exactitud con imágenes reales.

**Preparado para CI:** `.github/workflows/mono-eval.yml` (nombre `mono-tools`)
ejecuta esas pruebas en Ubuntu 24.04 con NumPy/OpenCV de la distribución y publica
el patrón SVG. El resultado del workflow debe comprobarse tras subir el cambio.

## OKVIS2 deja de ser el supuesto camino corto

**Verificado en fuente primaria, no por ejecución propia:** un contribuidor de
OKVIS2 explica que su inicialización de puntos no admite de forma robusta el caso
monocular, tampoco agregando IMU. Por tanto no basta con quitar una cámara del
YAML para adoptarlo como solución soportada. Fuente:
[issue 21 de OKVIS2, respuesta del 24-11-2025](https://github.com/ethz-mrl/okvis2/issues/21).
No hemos compilado ni ejecutado OKVIS2 y no afirmamos que cualquier adaptación sea
imposible. La opción B de ADR-0003 requiere reconsideración con esta evidencia.

## Candidato para el siguiente experimento: XRSLAM

**Verificado en archivos upstream:** [XRSLAM](https://github.com/openxrlab/xrslam)
declara Apache-2.0 para su propio código, con licencias separadas para sus
dependencias. Su [configuración EuRoC](https://github.com/openxrlab/xrslam/blob/main/configs/euroc_sensor.yaml)
define una cámara `cam0`, IMU, intrínsecos, extrínsecos y campos `camera_readout_time`
y `time_offset`. Sus [instrucciones](https://github.com/openxrlab/xrslam/blob/main/docs/en/get_started.md)
documentan reproducción EuRoC y salida TUM; esto permite plantear un experimento
comparable sin integrar todavía código en OpenView.

**No verificado:** build propio, calidad monocular, consumo ARM/Android, efecto
real de esos campos de rolling shutter y desfase, adaptación a VIT, ni todas las
licencias transitivas. Que existan campos de configuración no demuestra que el
algoritmo compense correctamente el sensor del Infinix. No se copió código de
XRSLAM ni se eligió todavía como dependencia del producto.

Siguiente experimento: fijar revisión de XRSLAM, auditar las dependencias mínimas,
ejecutar EuRoC monocular con referencia y contrastar error sin ajustar escala y
cobertura; solo después construir el adaptador VIT. En paralelo se necesita una
captura física del tablero para usar el calibrador ya implementado.

Referencia del método de calibración:
[OpenCV, Camera Calibration](https://docs.opencv.org/4.x/dc/dbb/tutorial_py_calibration.html).
